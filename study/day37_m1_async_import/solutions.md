# Day37 M1 Async Import · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实代码 `importjob/` 全包（Controller/Service/Worker/ImportJob/Repository/RetrySweeper）与 V3 迁移。**重试清扫已真实实现**（指数退避）；multipart/PDF 抽取/candidate/审核/lease 租约接管仍为目标设计，两部分严格分开。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（行号取自真实代码）：
- [ImportJobController.java:13-16](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobController.java)：POST（:20-21）建任务返回 202/Location，GET /{id}（:26）轮询，record CreateJobRequest/JobView（:32-33），类级 STUDENT（:15）；
- [ImportJobService.java:11-12](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobService.java)：submit（@Transactional :17）落库 RECEIVED，:27 注册 **afterCommit** 回调——事务提交后才调 worker.process；require 只读（:32）；
- [ImportJobWorker.java:7-8](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java)：`@Async("importTaskExecutor")`（:12）+ `@Transactional`（:13），process(id) :14：start（:18，attempt++、progress=10）→ 处理 → succeed（:22）/fail(msg)（:24）；
- [ImportJobRetrySweeper.java:26-28](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java)：`@Scheduled(fixedDelayString="${app.import.retry.sweep-ms:60000}")`（:43）默认 60s 扫一次；MAX_ATTEMPTS=3（:30）、BASE_BACKOFF_SECONDS=30（:31）、MAX_BACKOFF_SECONDS=600（:32）。

---

### Q2. 画上传到发布时序（目标 M1，标注当前缺口）。
```
上传文件(RECEIVED→STORED) → 建持久 import job(202+Location) → afterCommit 调度 worker
  → worker 领取(PROCESSING, attempt++, progress=10) → 文本抽取 → 解析为候选 candidate(REVIEWING)
  → 人工审核(ACCEPTED) → 创建新的 DRAFT paper_version → 走既有发布流程(PUBLISHED)
```
**当前已实现**：JSON sourceName 入口、afterCommit 调度、@Async 独立事务 worker、RECEIVED→PROCESSING→SUCCEEDED/FAILED 状态推进、**FAILED 任务的定时退避重试（最多 3 次，30/60/120s…封顶 600）**、@Version 乐观锁。
**当前缺口（不得写成已实现）**：没有真实 multipart 上传、字节落存储、文本抽取、candidate 与人工审核，worker 不创建 paper_version；**没有 lease/心跳/多 worker 接管**（清扫器只捞 FAILED 且 attempt<3 的任务，不处理卡在 PROCESSING 的僵尸任务，也没有 leaseUntil 列）；没有任务级死信（DLT 只存在于 Kafka Outbox 链路）。

---

### Q3. 设计 worker 崩溃恢复。
现状（已实现部分）：worker 失败走 fail(msg)→FAILED；ImportJobRetrySweeper 每 60s 查 `findByStatusAndAttemptLessThan(FAILED, 3)`，逐个比对 updatedAt 是否早于 `now - backoffSeconds(attempt)`（退避未到跳过），到点则 job.retry()（FAILED→RECEIVED、保留 error、attempt 已含失败次数）并跨 bean 调 worker.process（借代理走 @Async），backoffSeconds = `min(600, 30 << max(0,attempt-1))`。
仍缺的租约接管（目标设计，扩展 `leaseUntil/nextRunAt` 列）：
1. worker 领取时条件 UPDATE 置 PROCESSING 并写 `leaseUntil`（原子领取，影响行数=1 才执行）；
2. 处理中周期心跳续租；
3. 崩溃后 lease 过期，调度器扫描 `PROCESSING AND leaseUntil<now`，由其他 worker 接管（attempt+1，退避重试）——**现清扫器只扫 FAILED，此路径不存在**；
4. 超过最大次数（现常量 3）或不可恢复错误 → FAILED 等待人工；
5. 处理器必须幂等，重复执行不重复创建版本。

---

### Q4. 写出持久任务字段与轮询/SSE/WebSocket 协议差异。
- **真实表字段（[V3__async_import_jobs.sql](../../app/src/main/resources/db/migration/V3__async_import_jobs.sql)）**：id、owner_id、source_name、status、progress、error(VARCHAR500)、attempt、entity_version（@Version 乐观锁）、created_at、updated_at，索引 idx_import_job_owner_status(owner_id,status,updated_at) 支撑清扫扫描；
- **设计扩展字段（当前不存在）**：nextRunAt、leaseUntil、content_hash、storage_key；
- **轮询（当前采用，简单可靠）**：客户端定时 `GET /api/import-jobs/{id}` 读状态/进度/公开错误（前端 jobs.js 为 2s 间隔，SUCCEEDED/FAILED 停轮），无连接状态，缺点是有延迟和无效请求；
- **SSE**：服务器→客户端**单向**流（一个长连接，服务器推送事件），适合进度实时更新，基于 HTTP；
- **WebSocket**：**双向**全双工，适合客户端也要持续发消息的协作场景，成本更高。
进度推送按需求选型，本项目分钟级小任务用轮询已够。

---

### Q5. 写出 202 + Location 异步响应和任务查询 JSON。
```
HTTP/1.1 202 Accepted
Location: /api/import-jobs/17
{"id":17,"status":"RECEIVED","progress":0,"attempt":0}
```
轮询返回示例（数值对齐真实 start()：进入 PROCESSING 时 attempt++、progress=10）：
```json
{"id":17,"status":"PROCESSING","progress":10,"attempt":1,"error":null}
// 完成：
{"id":17,"status":"SUCCEEDED","progress":100,"attempt":1,"error":null}
// 失败等待退避重试：status=FAILED，error 为公开原因，attempt 为已失败次数
```
202 表示"已接受、尚未完成"，接口绝不阻塞到处理完成；查询只返回公开状态与错误，不暴露内部路径/堆栈。主流程由 M0FlowIntegrationTest.importJobIsAcceptedAndCompletesAsynchronously（:222）覆盖；重试算法 5 个边界用例在 ImportJobRetrySweeperTest（:39/:55/:69/:78/:87）。

---

### Q6. 画 worker 领取、崩溃、租约接管、最终失败时序。
现状时序（已实现，失败-重试路径）：
```
worker: afterCommit 触发 process → start(PROCESSING, attempt+1, progress=10)
      ├─ 成功 → succeed(SUCCEEDED)
      └─ 抛异常 → fail(FAILED, error)
sweeper(每60s): 查 FAILED 且 attempt<3 → 退避到点?
      ├─ 未到 → 跳过（不动）
      └─ 到点 → retry()回 RECEIVED（保留 error）→ 再触发 worker.process
              第 3 次仍失败（attempt=3）→ 永久 FAILED，等待人工
```
目标租约时序（设计，当前未实现）：
```
workerA: 条件UPDATE领取(lease=now+1m) → 处理中崩溃（未续租）
调度器: 扫描到 leaseUntil<now 且仍 PROCESSING
workerB: 条件UPDATE接管(attempt+1, 新lease) → 幂等重跑
        ├─ 成功 → SUCCEEDED(progress=100)
        └─ 可恢复失败 → 退避重试；超过上限/不可恢复 → FAILED + error
```

---

## External

### E1. 设计 hash 幂等。
上传时计算文件 SHA-256 contentHash，以 `(owner_id, content_hash)` 做业务唯一：相同文件重复上传直接复用已有任务/候选（返回原任务，不重复解析），既去重又幂等；注意 hash 相同只代表字节相同，仍要独立校验权限与状态。项目内可参照的唯一约束幂等模式：V4 outbox_event 的 uk_outbox_dedup(aggregate_type,aggregate_id,event_type)、consumed_event 的 uk_consumed_event_key(event_key)，以及提交侧 uk_submission_question。

### E2. 比较轮询 / SSE。
| 维度 | 轮询 | SSE |
|---|---|---|
| 方向 | 客户端反复请求 | 服务器单向推送 |
| 实时性 | 取决于间隔（本项目 2s） | 近实时 |
| 连接 | 短连接、无状态、穿透代理容易 | 长连接，需处理断线重连(EventSource 自带) |
| 成本 | 无效请求多 | 连接占用，规模大时需考量 |
| 适用 | 低频状态查询（本项目任务进度） | 高频进度/通知流 |
导入任务分钟级、数量小，轮询足够且最简单。
