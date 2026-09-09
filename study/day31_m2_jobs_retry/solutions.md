# Day31 M2 Jobs & Retry · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `ImportJob/ImportJobStatus/ImportJobService/ImportJobWorker/V3`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`ImportJobStatus` 四状态；`ImportJob` 8-39 行字段与 start/succeed/fail；`ImportJobController` 202+Location；`ImportJobService` 17-30 行事务保存 + afterCommit 调度；`ImportJobWorker` 12-27 行 @Async + 独立事务；V3 任务表。

---

### Q2. 运行 POST /api/import-jobs 并轮询，验证 202/Location/RECEIVED/SUCCEEDED/progress，说明 afterCommit 作用。
真实流程：
1. `POST /api/import-jobs`：Service 在事务内 `jobs.save(new ImportJob(ownerId, sourceName))`（初始 RECEIVED、progress=0、attempt=0），并 `registerSynchronization(afterCommit → worker.process(jobId))`；Controller 返回 **202 Accepted** 与 `Location: /api/import-jobs/{id}`；
2. **afterCommit 的作用**：worker 只在事务提交后才被调度，避免异步线程在 INSERT 尚未提交、其他数据库连接还读不到该行时就去查询（竞态）；
3. worker（`@Async("importTaskExecutor")` + 独立 `@Transactional`）：只处理 RECEIVED，`start()`→PROCESSING/attempt+1/progress=10，确定性处理后 `succeed()`→SUCCEEDED/progress=100，异常 `fail(msg)`→FAILED 并写 error；
4. 客户端 `GET /api/import-jobs/{id}` 轮询得到状态与 progress，attempt 从查询 JSON 读取。

---

### Q3. 用另一学生 token 查第一个学生任务，验证资源隔离；检查 V3 外键/索引/@Version。
- `require(user,id)` 用 `findByIdAndOwnerId(id, user.userId())`，查不到即 404 NOT_FOUND（不泄露他人任务存在性）；
- V3 真实结构：外键 `fk_import_job_owner (owner_id→user_account.id)`、唯一键 `idx_import_job_owner_created(owner_id,id)`、查询索引 `idx_import_job_owner_status(owner_id,status,updated_at)`；实体 `@Version entityVersion` 乐观锁，`@PreUpdate` 自动刷 updatedAt。

---

### Q4. 设计 PDF 导入状态机。
扩展状态机：`RECEIVED → PROCESSING（下载/解析/校验/逐题入库，progress 递增）→ SUCCEEDED`；可恢复失败进入 `RETRYING`，不可恢复（文件损坏、格式非法、权限不足）进入 `FAILED/死信`。每个阶段推进都落库，使进度与错误可查询、可恢复；真实 PDF 解析替换当前 worker 的确定性占位逻辑。

---

### Q5. 写租约恢复、任务字段、指数退避与死信条件。
扩展字段（study 明确属后续扩展，非当前表）：`attempt、nextRunAt、leaseUntil、lastError`。
- **租约**：worker 领取时写 `leaseUntil=now+租约`，周期心跳续租；只有租约过期才允许其他 worker 接管（防止崩溃任务永久卡在 PROCESSING）；
- **指数退避 + 抖动**：`delay = min(maxDelay, base * 2^attempt) + randomJitter`，避免失败任务同时重试打爆下游；
- **死信条件**：参数错误、权限错误、损坏文件这类**重试无意义**的不可恢复失败，超过最大 attempt 后进入死信队列，等待人工处理，而不是无限重试。

---

### Q6. 写出 worker 条件 UPDATE，解释租约过期接管条件。
```sql
UPDATE import_job
SET status='PROCESSING', lease_until=NOW() + INTERVAL 1 MINUTE, attempt=attempt+1, updated_at=NOW()
WHERE id = ?
  AND status IN ('RECEIVED','RETRYING')
  AND next_run_at <= NOW();
```
影响行数=1 才说明本 worker 抢到任务（原子领取，避免多 worker 重复执行）；接管 RUNNING/PROCESSING 任务的条件是 `status='PROCESSING' AND lease_until < NOW()`（原 worker 心跳超时、判定失联）。

---

### Q7. 写出至少一次投递下的幂等结果键。
消息/任务通常是**至少一次（at-least-once）**投递，处理器可能重复运行，因此处理逻辑必须幂等：用业务唯一键（如 `(owner_id, source_name, 批次)` 或题库版本号唯一键）做“存在即跳过/转为更新”，重复执行同一 job 不会重复创建题库版本、重复扣费或覆盖他人结果。任务表是事实来源，线程池只是执行资源。

---

### Q8. 画 producer/broker/consumer ack/重试/死信时序；比较 RabbitMQ / Kafka / Outbox。
**通用时序**：producer 发送并等 confirm → broker 持久化 → consumer 拉取处理 → 成功 ack；处理失败按策略重试（退避），超过次数进死信队列（DLQ）；消费端必须幂等。“发送成功”不等于业务事务提交，也不等于消费完成。

- **RabbitMQ**：`producer → exchange（按 routing key/binding 路由）→ queue → consumer`；靠 publisher confirm 保证到达、手动 ack、DLX 死信；
- **Kafka**：`producer → topic（分多个 partition）→ consumer group 按 offset 提交`；顺序只在**同一 partition 内**成立，按 key 分区保证同 key 有序；
- **Transactional Outbox**：在**同一数据库事务**里写业务表 + outbox 事件表，事务提交后由独立 publisher 轮询 outbox 投递到 MQ，投递后标记。它把“业务提交”和“发消息”的双写丢失窗口降到最低，但 publisher 可能重复投递，仍需消费幂等。

---

## External

### E1. 模拟重复 worker。
开两个 worker 实例同时领取同一 job：用 Q6 条件 UPDATE，只有一个影响行数为 1；若用“先 SELECT 再 UPDATE”非原子写法，两者都可能认为自己领到任务（重复执行），以此验证原子领取与处理幂等的必要性。

### E2. 设计人工重放。
FAILED/死信任务保留原始输入（sourceName/文件引用）、lastError、attempt；管理端提供“重放”动作：校验权限后把状态重置为 RETRYING/RECEIVED、nextRunAt=now、清空或保留错误记录，走同一处理器（幂等保证不产生重复副作用）；重放动作本身审计留痕。

### E3. 发送失败/消费失败/重复消费的处理。
- 发送失败：producer confirm 未到 → 重发（Outbox 模式下由轮询兜底）；
- 消费失败：可恢复错误 nack/requeue 退避重试，不可恢复直接进 DLQ，避免毒消息无限循环；
- 重复消费：用业务幂等键去重，已处理过的消息直接 ack 跳过。

### E4. Outbox 最小字段与重复投递后状态转换。
`id, aggregate_id, event_type, payload, status(NEW/SENT), created_at, sent_at, retry_count`；publisher 扫描 NEW → 投递 MQ → 成功置 SENT（或删除）；投递崩溃导致重复投递时，消费端按 event id 幂等，状态转换始终 NEW→SENT 单向，重复发送不改变业务结果。
