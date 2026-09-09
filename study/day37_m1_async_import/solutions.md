# Day37 M1 Async Import · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `ImportJobController/Service/Worker`（当前基线）与 M1 目标设计。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`ImportJobController` 21-33 行 202/Location/轮询；`ImportJobService` 18-30 行持久化 + afterCommit 调度；`ImportJobWorker` 14-27 行 @Async 独立事务。

---

### Q2. 画上传到发布时序（目标 M1，标注当前缺口）。
```
上传文件(RECEIVED→STORED) → 建持久 import job(202+Location) → afterCommit 调度 worker
  → worker 领取(PROCESSING) → 文本抽取 → 解析为候选 candidate(REVIEWING)
  → 人工审核(ACCEPTED) → 创建新的 DRAFT paper_version → 走既有发布流程(PUBLISHED)
```
**当前缺口（不得写成已实现）**：现有 worker 只把 `sourceName` 任务从 RECEIVED 推进到 SUCCEEDED/FAILED（一次尝试、确定性占位），没有真实 multipart 上传、文本抽取、candidate、审核，也不创建 paper_version；lease/心跳/重试/死信是后续扩展。

---

### Q3. 设计 worker 崩溃恢复。
扩展任务字段 `status/progress/error/attempt/nextRunAt/leaseUntil`：
1. worker 领取时条件 UPDATE 置 PROCESSING 并写 `leaseUntil`（原子领取，影响行数=1 才执行）；
2. 处理中周期心跳续租；
3. 崩溃后 lease 过期，调度器扫描 `PROCESSING AND leaseUntil<now`，由其他 worker 接管（attempt+1，退避重试）；
4. 超过最大次数或不可恢复错误 → FAILED/死信，等待人工；
5. 处理器必须幂等，重复执行不重复创建版本。

---

### Q4. 写出持久任务字段与轮询/SSE/WebSocket 协议差异。
- **持久任务字段**：status、progress、error、attempt、nextRunAt、leaseUntil、owner、输入标识、created/updated；
- **轮询（当前采用，简单可靠）**：客户端定时 `GET /api/import-jobs/{id}` 读状态/进度/公开错误，无连接状态，缺点是有延迟和无效请求；
- **SSE**：服务器→客户端**单向**流（一个长连接，服务器推送事件），适合进度实时更新，基于 HTTP；
- **WebSocket**：**双向**全双工，适合客户端也要持续发消息的协作场景，成本更高。
进度推送按需求选型，本项目轮询已够。

---

### Q5. 写出 202 + Location 异步响应和任务查询 JSON。
```
HTTP/1.1 202 Accepted
Location: /api/import-jobs/17
{"id":17,"status":"RECEIVED","progress":0,"attempt":0}
```
轮询返回示例：
```json
{"id":17,"status":"PROCESSING","progress":10,"attempt":1,"error":null}
// 完成：
{"id":17,"status":"SUCCEEDED","progress":100,"attempt":1,"error":null}
```
202 表示“已接受、尚未完成”，接口绝不阻塞到 PDF 解析完；查询只返回公开状态与错误，不暴露内部路径/堆栈。

---

### Q6. 画 worker 领取、崩溃、租约接管、最终失败时序。
```
workerA: 条件UPDATE领取(lease=now+1m) → 处理中崩溃（未续租）
调度器: 扫描到 leaseUntil<now 且仍 PROCESSING
workerB: 条件UPDATE接管(attempt+1, 新lease) → 幂等重跑
        ├─ 成功 → SUCCEEDED(progress=100)
        └─ 可恢复失败 → RETRYING(nextRunAt=退避)；超过上限/不可恢复 → FAILED + lastError（死信）
```

---

## External

### E1. 设计 hash 幂等。
上传时计算文件 SHA-256 contentHash，以 `(owner_id, content_hash)` 做业务唯一：相同文件重复上传直接复用已有任务/候选（返回原任务，不重复解析），既去重又幂等；注意 hash 相同只代表字节相同，仍要独立校验权限与状态。

### E2. 比较轮询 / SSE。
| 维度 | 轮询 | SSE |
|---|---|---|
| 方向 | 客户端反复请求 | 服务器单向推送 |
| 实时性 | 取决于间隔 | 近实时 |
| 连接 | 短连接、无状态、穿透代理容易 | 长连接，需处理断线重连(EventSource 自带) |
| 成本 | 无效请求多 | 连接占用，规模大时需考量 |
| 适用 | 低频状态查询（本项目任务进度） | 高频进度/通知流 |
导入任务分钟级、数量小，轮询足够且最简单。
