# Day30 M2 Idempotency · 题目与标准解答（Solutions）

> 依据：`study.md` + 项目内三处**已真实实现**的幂等对照物——M0 `practice_session.submission_key`（业务行幂等）、event 包 `outbox_event` 的 uk_outbox_dedup（生产端去重）、`consumed_event`（消费端幂等表，V4 建表）；通用 `idempotency_record` 表本身仍是**设计方案，尚未实现**，必须区分。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
对照锚点（已实现）：`PracticeService.submit:56-89`（key 寄生业务行 + 行锁串行）；[event/OutboxPublisher.java:54-78](../../app/src/main/java/com/allen/questionbank/event/OutboxPublisher.java)（同事务写 outbox，原生 SQL `ON DUPLICATE KEY UPDATE id=id`）；[event/PaperPublishedEventConsumer.java:32-58](../../app/src/main/java/com/allen/questionbank/event/PaperPublishedEventConsumer.java)（existsByEventKey → save ConsumedEvent）；[V4__outbox_events.sql:21-27](../../app/src/main/resources/db/migration/V4__outbox_events.sql) consumed_event 表与 uk_consumed_event_key。**未实现**：跨接口的通用 idempotency_record（本主题的设计产出）。

---

### Q2. 画两个实例抢同一 key。
```
实例A、实例B 同时收到相同 (scope,business_key) 请求：
  都尝试 INSERT idempotency_record(PROCESSING)
     ├─ A：INSERT 成功（拿到执行权）→ 执行业务 → UPDATE 为 SUCCEEDED + 存响应
     └─ B：主键/唯一键冲突 → 转为 SELECT 已有记录：
             · PROCESSING → 返回 409/处理中（或短暂等待轮询）
             · SUCCEEDED 且 request_hash 相同 → 直接重放 response_body
             · SUCCEEDED 但 request_hash 不同 → 422/409（同 key 不同请求体）
```
跨实例互斥由**数据库唯一键**保证，不依赖 JVM 锁。项目内同一模式的两个真实实例：①消费端 `consumed.save(new ConsumedEvent(eventKey))`（Consumer:56），并发重复消费时 uk_consumed_event_key 让后到者撞键，existsByEventKey（:52）是廉价快路径；②生产端 OutboxPublisher 先 existsBy 快路径（:56），并发下由 `INSERT ... ON DUPLICATE KEY UPDATE id=id`（:31-36）单语句裁决，撞键静默跳过且不污染业务事务。

---

### Q3. 设计摘要冲突。
`request_hash = SHA-256(规范化后的请求体)`。同一 business_key 第二次请求：
- hash 与首次相同 → 同一业务操作的重试，返回缓存响应（幂等重放，对照 M0 同 key 重放 submission_result_json，PracticeService:63-65）；
- hash 不同 → 客户端用同一幂等键发起了**不同请求**，编程错误，返回 422（或 409），不能覆盖首次结果（对照 M0 异键 409 STATE_CONFLICT，:64）。
项目现状：M0 只比对幂等键本身、不做请求体摘要（IDEMPOTENCY_KEY_REQUIRED 只校验存在与长度）；event 侧的“键”是确定性 eventKey=`aggregateType:aggregateId:eventType`（OutboxEvent.eventKey :84-86），同键即同义，不需要 hash。request_hash 是通用表设计才需要的增量能力。

---

### Q4. 写出幂等记录模型（scope/business_key/request_hash/status/响应/过期）。
| 字段 | 作用 | 项目内对照 |
|---|---|---|
| scope | 业务域/接口（如 `practice.submit`），与 key 组成主键，避免跨接口串号 | consumed_event 只服务事件消费（单 scope）；outbox 的 aggregate_type 起类似作用 |
| business_key | 幂等键（常含用户 id+资源 id+客户端 UUID），绑定用户/操作/资源 | practice_session.submission_key（V1:57，长度 100）；consumed_event.event_key（V4:24，长度 190 适配 utf8mb4 索引上限） |
| request_hash | 请求体摘要，检测同 key 不同请求 | **通用表设计项，项目未实现** |
| status | PROCESSING / SUCCEEDED / FAILED | outbox_event.status=PENDING/SENT/FAILED（OutboxEvent:21-23）是最接近的三态实现 |
| response_code / response_body | 首次完成后的响应，用于重放 | practice_session.submission_result_json（TEXT，SubmitResult 快照） |
| lease_until | 处理中租约到期时间，供超时接管 | **未实现**；最接近的是 ImportJob 的 attempt/updated_at + RetrySweeper 退避扫描 |
| created_at / expires_at | 创建时间与清理时间（TTL 只控制清理） | consumed_at（V4:26，updatable=false）；项目无过期清理任务 |

key 必须绑定用户与资源：只用裸 UUID 可能把 A 用户的响应重放给 B 用户（M0 因此在 :62 额外校验 studentId 归属）。

---

### Q5. 写出幂等表 DDL 和唯一键竞争后的两条事务分支。
设计 DDL（通用表，未实现）：
```sql
CREATE TABLE idempotency_record (
    scope         VARCHAR(100) NOT NULL,
    business_key  VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    response_code INT,
    response_body JSON,
    lease_until   TIMESTAMP(6) NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (scope, business_key)
);
```
项目内**已落地的真实 DDL 对照**（[V4__outbox_events.sql:21-27](../../app/src/main/resources/db/migration/V4__outbox_events.sql)）：
```sql
CREATE TABLE consumed_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(190) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_consumed_event_key UNIQUE (event_key)
);
```
**分支一（插入成功=执行者）**：INSERT PROCESSING（设 lease_until）→ 业务事务内写业务事实 → 提交后置 SUCCEEDED 并写 response（顺序见 Q6）。对照消费者：eventKey 不存在 → 业务处理与 `consumed.save` 在同一 @Transactional（Consumer:33/:56），一起提交。
**分支二（唯一键冲突=重复请求）**：捕获 DuplicateKeyException → SELECT → 按 status/request_hash 决定重放、409 处理中、422 摘要冲突。对照 OutboxPublisher 的工程化做法：不用 JPA save（insert 延迟 flush，异常在事务边界外 catch 不到；saveAndFlush 失败后 Hibernate 会话不可信），而用 `INSERT ... ON DUPLICATE KEY UPDATE id=id` 单语句让撞键变成“0 行受影响、语句成功”，业务事务继续提交（注释 OutboxPublisher:20-30）；不用 INSERT IGNORE，避免把超长等其他错误也吞成 warning。

---

### Q6. 设计 PROCESSING 超时接管与过期清理边界，并定义业务事实与响应的提交顺序。
- **超时接管**：PROCESSING 记录 `lease_until < now` 说明执行实例可能崩溃；其他实例用条件 UPDATE（`WHERE status='PROCESSING' AND lease_until<now`）抢占重试，前提是**业务处理器幂等**。项目内可对照的真实重试器是 OutboxRelay（:46-68）：@Scheduled 每 1s（app.outbox.poll-interval-ms 默认 1000）拉 PENDING，send().get() 成功才 markSent；失败 markFailed（retryCount+1、错误截断 480 字符，OutboxEvent:72-76），未超 app.outbox.max-attempts（默认 5）就 requeue 回 PENDING 下轮再试，超限 log.error 等人工介入；InterruptedException 恢复中断标志并退出本轮（:54-56）。
- **过期清理**：`expires_at` 只决定何时物理删除；保留期必须 ≥ 客户端最大重试窗口。项目现状：consumed_event/outbox_event 的 SENT 行**没有**清理任务（保留作审计），这是显式选择，通用表设计才需要分批清理。
- **提交顺序（关键一致性）**：业务事实与幂等记录在**同一数据库事务**内提交——这正是 Transactional Outbox 的核心：BankService.publish 在业务事务内 enqueue outbox（:75-78），一起 commit/rollback，“改了库但消息没发”结构上不可能；消费者业务处理与 consumed_event 登记同事务（Consumer:33），回滚则消息会被 Kafka 重投。若响应需 commit 后生成，可补写 response，但执行权与业务事实必须同事务落定。

---

## External

### E1. 比较 SETNX 与 MySQL 唯一键。
| 维度 | Redis SET NX EX/PX | MySQL 唯一键 INSERT |
|---|---|---|
| 互斥范围 | 快、单命令原子，依赖 Redis 可用性 | 与业务库同库，天然和业务事务一致 |
| 持久化 | 需 AOF/集群与过期策略，重启可能丢 | 随数据库持久化，强一致 |
| 响应存储 | 适合放缓存（设 TTL） | 可直接存 JSON，便于审计/长期重放 |
| 项目实例 | RedisLockService.tryLock（SET NX PX + INCR fencing，:53-62）、提交路径可选 Redis 锁守卫 | uk_consumed_event_key、uk_outbox_dedup、uk_submission_question、practice_session.submission_key |
| 适用 | 高频、短窗口、可容忍缓存语义 | 要求与业务事实强一致的幂等 |
二者可组合：DB 唯一键做权威去重，Redis 做热点挡重——本项目提交路径正是这个组合（RedisGuardedPracticeSubmitter 外层减压 + DB 行锁/唯一键兜底，绕过 Redis 锁仍保正确，RedisGuardedSubmitIntegrationTest:84）。

### E2. 设计清理任务。
定时任务分批删除 `expires_at < now` 的 SUCCEEDED/FAILED 记录（小批量 LIMIT，避免大事务锁表）；PROCESSING 不按 expires_at 直接删，先走租约接管判定，确认执行方死亡再标 FAILED 或重试；清理进度可观测（删除条数、最老记录年龄），保留足够审计窗口。项目内可复用的真实骨架是 ImportJobRetrySweeper（@Scheduled fixedDelay `${app.import.retry.sweep-ms:60000}`、findByStatusAndAttemptLessThan 候选查询、退避 30×2^(n-1) 封顶 600s、MAX_ATTEMPTS=3）与 OutboxRelay 的轮询-重试结构；注意 consumed_event/outbox_event 当前刻意不清理，若加清理任务必须新增迁移版本（V5 起）而不能改 V4。
