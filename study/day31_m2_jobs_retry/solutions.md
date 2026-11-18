# Day31 M2 Jobs & Retry · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 importjob 包 7 类（ImportJob/ImportJobStatus/ImportJobController/ImportJobService/ImportJobWorker/ImportJobRepository/**ImportJobRetrySweeper（重构后已实现）**/V3），以及 event 包真实 Outbox。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`ImportJobStatus` 四状态 RECEIVED/PROCESSING/SUCCEEDED/FAILED（**无 RETRYING**）；[ImportJob.java:6-42](../../app/src/main/java/com/allen/questionbank/importjob/ImportJob.java)（字段 :9-19、@Version :19、@PrePersist/@PreUpdate :26-27、start :37/succeed :38/fail :39/retry :41）；[ImportJobController.java:13-35](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobController.java)（类级授权 :17、POST :22-26 返回 202+Location、GET :28-29、JobView 含 attempt :35）；[ImportJobService.java:17-30](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobService.java) 事务保存 + afterCommit 调度（:26-28）、:32-36 资源隔离查询；[ImportJobWorker.java:12-27](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java)（@Async("importTaskExecutor") :12 + 独立 @Transactional :13，只处理 RECEIVED :16）；[ImportJobRetrySweeper.java:26-65](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java)（**真实重试清扫器**）；V3 任务表。

---

### Q2. 运行 POST /api/import-jobs 并轮询，验证 202/Location/RECEIVED/SUCCEEDED/progress，说明 afterCommit 作用。
真实流程（M0FlowIntegrationTest.importJobIsAcceptedAndCompletesAsynchronously :222 固化）：
1. `POST /api/import-jobs`：ImportJobService.submit 在 @Transactional 内先校验 sourceName（非空且 ≤200，:19-20），`jobs.save(new ImportJob(ownerId, sourceName))`（初始 RECEIVED、progress=0、attempt=0），并 registerSynchronization（afterCommit → worker.process(jobId)，:26-28）；Controller 返回 **202 Accepted** 与 `Location: /api/import-jobs/{id}`，响应体 JobView 含 attempt；
2. **afterCommit 的作用**：worker 只在事务提交后才被调度，避免异步线程在 INSERT 尚未提交、其他连接读不到该行时就查询（注释 :23-25）；
3. worker（@Async 指定 importTaskExecutor 池 + 独立 @Transactional）：只处理 RECEIVED（:16，其余状态直接 return，天然不重复执行），`start()`→PROCESSING/attempt+1/progress=10（:18/:37），确定性占位处理后 `succeed()`→SUCCEEDED/progress=100/清 error（:22/:38），RuntimeException 被 catch 后 `fail(msg)`→FAILED 写 error（:23-25/:39），最后统一 save（:26）；
4. 客户端 `GET /api/import-jobs/{id}` 轮询得到状态/progress/attempt。

---

### Q3. 用另一学生 token 查第一个学生任务，验证资源隔离；检查 V3 外键/索引/@Version。
- `require(user,id)` 用 `findByIdAndOwnerId(id, user.userId())`（ImportJobRepository:8），查不到即 404 NOT_FOUND（ImportJobService:34-35），不泄露他人任务存在性；
- V3 真实结构（[V3__async_import_jobs.sql](../../app/src/main/resources/db/migration/V3__async_import_jobs.sql) 全 15 行）：外键 `fk_import_job_owner(owner_id→user_account.id)`、唯一键 `idx_import_job_owner_created(owner_id,id)`、查询索引 `idx_import_job_owner_status(owner_id,status,updated_at)`（供清扫/列表）；实体 `@Version entityVersion`（ImportJob:19）乐观锁，@PreUpdate 自动刷 updatedAt（:27）——重试清扫的退避计时正是以 updatedAt 为起点。

---

### Q4. 设计 PDF 导入状态机。
当前真实状态机：`RECEIVED → PROCESSING → SUCCEEDED`，处理异常 → `FAILED`；**FAILED 不是终态**——ImportJobRetrySweeper 会把退避窗口已过且 attempt<3 的任务 `retry()` 翻回 RECEIVED（ImportJob:41），重新走同一状态机；attempt≥3 的 FAILED 才是人工介入终态。扩展真实 PDF：在 PROCESSING 内细分下载/解析/校验/逐题入库并递增 progress，文件损坏/格式非法/权限不足这类不可恢复错误应直接 fail 且不被重试（需要在 worker 内区分可恢复/不可恢复异常，当前 worker 对所有 RuntimeException 一律 fail、由清扫器按次数兜底）。

---

### Q5. 写租约恢复、任务字段、指数退避与死信条件。
**重构后真实实现（无 leaseUntil/nextRunAt 列）**：
- 真实字段：status、progress、error、attempt、created_at、updated_at、entity_version（V3 全 11 列）；
- **指数退避（ImportJobRetrySweeper.backoffSeconds :61-64）**：第 n 次尝试失败后最小等待 `30 × 2^(n-1)` 秒，即 30s→60s→120s→240s…，封顶 **600s（10 分钟）**；等待起点取 updatedAt（fail 落库时 @PreUpdate 刷新），清扫时 `updatedAt.isAfter(now-backoff) 就跳过`（:50-51）；
- **最大尝试 MAX_ATTEMPTS=3**（:30）：候选查询 `findByStatusAndAttemptLessThan(FAILED, 3)`（:45/Repository:11），attempt≥3 的任务保持 FAILED、error 保留最后原因，等人工介入，不无限重试毒丸；
- **调度参数**：@Scheduled fixedDelayString=`${app.import.retry.sweep-ms:60000}`（默认 60s 一轮，:43），@ConditionalOnProperty `app.import.retry.enabled`（matchIfMissing=true，默认开启，:27）；
- **仍是设计、未实现**：leaseUntil/worker 心跳/条件 UPDATE 原子领取（见 Q6）、真实死信队列。当前是“单实例定时扫表 + @Version 乐观锁”的恢复模型，崩溃 worker 留下的 FAILED 由下一轮清扫补扫（修复前 FAILED 永久停留，类注释 :11-13 记录了这一修复背景）。

---

### Q6. 写出 worker 条件 UPDATE，解释租约过期接管条件。
以下是**多 worker 部署的设计形态（项目未实现，当前用扫表+乐观锁替代）**：
```sql
UPDATE import_job
SET status='PROCESSING', lease_until=NOW() + INTERVAL 1 MINUTE, attempt=attempt+1, updated_at=NOW()
WHERE id = ?
  AND status IN ('RECEIVED','RETRYING')
  AND next_run_at <= NOW();
```
影响行数=1 才算抢到（原子领取，避免多 worker 重复执行）；接管 PROCESSING 的条件是 `status='PROCESSING' AND lease_until < NOW()`（原 worker 心跳超时、判定失联）。
**项目当前的真实等价物**：清扫器单线程定时跑，`job.retry(); jobs.save(job)` 时 @Version 防并发覆盖（两个清扫实例同时保存，后到者抛乐观锁失败而不是双执行）；翻转后**跨 bean** 调 `worker.process(id)`（:54）——@Async 代理只在跨 bean 调用时生效，重活交给 import 池而不是占用调度线程（同 bean 内 this 调用会绕过代理，这是清扫器单出一个组件的原因，类注释 :21-23）。importTaskExecutor 池参数 core=2/max=4/queue=100/CallerRunsPolicy（InfrastructureConfig:22-33）。

---

### Q7. 写出至少一次执行下的幂等结果键。
任务/消息通常是**至少一次（at-least-once）**，处理器可能重复运行，处理逻辑必须幂等：worker 用状态门禁（只处理 RECEIVED，:16）保证重复 process 不重复执行；扩展真实入库时要用业务唯一键（题库 (bank_id,version_no)、uk_paper_version_no）做“存在即跳过/转更新”，重复执行同一 job 不会重复创建版本。任务表是事实来源，线程池只是执行资源。消息侧的真实幂等键是 consumed_event.event_key（eventKey=`aggregateType:aggregateId:eventType`），见 day30。

---

### Q8. 画 producer/broker/consumer ack/重试/死信时序；比较 RabbitMQ / Kafka / Outbox。
**通用时序**：producer 发送并等 confirm → broker 持久化 → consumer 拉取处理 → 成功 ack；失败按退避重试，超限进死信；消费端必须幂等。“发送成功”不等于业务事务提交，也不等于消费完成。
- **RabbitMQ（通用知识，项目未使用）**：`producer → exchange（routing key/binding 路由）→ queue → consumer`；publisher confirm、手动 ack、DLX 死信；
- **Kafka（项目已真实接入，event 包）**：`producer → topic（多 partition）→ consumer group 按 offset 提交`；顺序只在同 partition 内成立；本项目 topic=`qb-events`、group=`question-bank`、发送按 eventKey 作 key（同键恒定落同分区，并发度 3 要求分区数≥3，KafkaConsumerConfig:32-36 注释）；消费失败 DefaultErrorHandler + FixedBackOff(200ms,2 次重试) 后进 `<topic>.DLT`（:38-40）；
- **Transactional Outbox（项目已真实实现）**：业务事务内同写业务表+outbox_event（OutboxPublisher 原生 INSERT…ON DUPLICATE KEY），提交后 OutboxRelay 每 1s（app.outbox.poll-interval-ms 默认 1000）轮询 PENDING，kafkaTemplate.send().get() 成功才 markSent；失败 markFailed（retryCount+1），未超 app.outbox.max-attempts（默认 5）requeue 回 PENDING，超限 error 日志等人工；消费端 consumed_event 幂等。不丢靠 outbox+至少一次，不重靠消费幂等，DLT 处理“合法但持续处理失败”的第三种情况。

---

## External

### E1. 模拟重复 worker。
两个清扫/worker 实例同时面对同一 FAILED 任务：当前模型下候选查询两边都能读到，但 `jobs.save` 时 @Version 让后到者乐观锁失败（不会双重入队）；若改成 Q6 条件 UPDATE，则只有一个影响行数=1。用“先 SELECT 再 UPDATE”非原子写法则两边都可能自认为领到任务（重复执行），以此验证原子领取/乐观锁与处理幂等的必要性。ImportJobRetrySweeperTest 5 个纯单元用例（默认运行）固化退避语义：failedJobPastBackoffWindowIsRequeued（:40）、failedJobInsideBackoffWindowIsSkipped（:56）、secondFailureWaitsForExponentialBackoff（:70）、emptyCandidateListShortCircuitsWithoutSave（:79）、backoffGrowsExponentiallyAndCapsAtTenMinutes（:88）。

### E2. 设计人工重放。
FAILED 终态任务保留 sourceName、error、attempt、时间戳；管理端“重放”动作：校验权限后把状态重置为 RECEIVED（等价 job.retry()）、attempt 归零或保留，走同一 worker（状态门禁+业务唯一键保证不产生重复副作用）；重放动作审计留痕。当前项目没有管理端重放端点，属设计项。

### E3. 发送失败/消费失败/重复消费的处理（对照项目真实实现）。
- 发送失败：OutboxRelay send 抛异常 → markFailed+requeue，下轮重发（PENDING 不丢）；InterruptedException 恢复中断标志并退出本轮（:54-56）；
- 消费失败：PaperPublishedEventConsumer 区分畸形消息（无法解析/缺 eventKey → warn 跳过，:38-46）与毒丸（合法但业务失败 payload.fail=true → 抛异常 → FixedBackOff 重试 2 次 → DLT，:49-51）；
- 重复消费：existsByEventKey 命中即跳过（:52-55），uk_consumed_event_key 最终兜底。

### E4. Outbox 最小字段与重复投递后状态转换。
项目真实 outbox_event 字段（V4:1-18）：id、aggregate_type、aggregate_id、event_type、payload_json(TEXT)、status(PENDING/SENT/FAILED)、retry_count、last_error、created_at、sent_at + uk_outbox_dedup(aggregate_type,aggregate_id,event_type)。状态转换：PENDING→SENT（markSent 写 sentAt，单向）、PENDING→FAILED→（requeue）PENDING；重复投递不改变业务结果（消费端 eventKey 幂等）；重复入队被 uk_outbox_dedup + ON DUPLICATE KEY UPDATE id=id 静默合并。
