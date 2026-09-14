# MustRemember

- 通用幂等表字段是 `scope`、`business_key`、`request_hash`、`status`、`response_code`、`response_body`、`created_at`、`expires_at`、`lease_until`；**该通用表本身仍属设计、未实现**
- 项目已落地三处真实幂等：①practice_session.submission_key（业务行幂等 + FOR UPDATE 行锁）；②outbox_event 的 uk_outbox_dedup（生产端，INSERT…ON DUPLICATE KEY UPDATE id=id 单语句裁决）；③consumed_event.uk_consumed_event_key（消费端，existsByEventKey 快路径 + save 撞键兜底）
- 唯一键竞争模式是 `INSERT`；插入成功者执行，唯一键冲突者读取已有记录；相同摘要返回已有响应，不同摘要返回 422/409；跨实例互斥只靠数据库唯一键，不靠 JVM 锁
- `PROCESSING/SUCCEEDED/FAILED` 状态区分处理中、已完成和可重试失败；项目最接近的三态是 outbox_event 的 PENDING/SENT/FAILED（OutboxEvent:21-23），OutboxRelay 轮询重试（默认间隔 1s、max-attempts 5、FixedBackOff 式 requeue）
- 幂等记录必须与业务事实同事务提交（Transactional Outbox 核心）：BankService.publish 在业务事务内 enqueue（:75-78），消费者业务处理与 consumed.save 同 @Transactional（Consumer:33/:56）
- Kafka 事实：topic=`qb-events`（app.outbox.topic）、group=`question-bank`（app.kafka.group-id）、DLT=`<topic>.DLT`（DefaultErrorHandler FixedBackOff(200ms,2 次)，KafkaConsumerConfig:38-40）、并发 3、发送按 eventKey 作 key 保同键有序；全部 event 组件由 app.kafka.enabled=true 条件装配
- 表结构的核心约束是：
  ```sql
  -- 设计中的通用表（未实现）
  CREATE TABLE idempotency_record (
      scope VARCHAR(100), business_key VARCHAR(128), request_hash CHAR(64),
      status VARCHAR(20), response_body JSON, lease_until TIMESTAMP NULL,
      PRIMARY KEY (scope, business_key)
  );
  -- 已实现的消费端幂等表（V4__outbox_events.sql:20-27）
  CREATE TABLE consumed_event (
      id BIGINT AUTO_INCREMENT PRIMARY KEY,
      event_key VARCHAR(190) NOT NULL,
      consumed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
      CONSTRAINT uk_consumed_event_key UNIQUE (event_key)
  );
  ```
- 外部源码索引（MustRemember）：[Stripe idempotency_key](https://docs.stripe.com/api/idempotent_requests) 的请求键与响应重放契约；[MySQL UNIQUE](https://dev.mysql.com/doc/refman/8.4/en/constraint-primary-key.html) 的唯一键冲突语义

# MustUnderstand

- M0 的 key 存在 `practice_session`（只覆盖提交），event 包的 consumed_event 覆盖消息消费，M2 通用表才抽象跨接口、资源和实例的幂等记录；三者抽象层级不同，不要混为一谈
- key 必须绑定用户、操作、资源和请求语义；只用裸 key 可能把其他用户的响应读回（M0 因此额外校验 studentId 归属）
- TTL 只控制清理，不代表业务响应可以安全遗忘；重试窗口、客户端超时和保留期要一致；consumed/outbox 的 SENT 行当前刻意不清理（审计），若加清理须新增 V5+ 迁移
- 证据分层：OutboxTest 9 法（生命周期/截断/确定性 key/重复跳过/序列化失败连坐/blank 拒绝/仓储精确匹配）与 OutboxRelayAndConsumerTest 5 法（投递成功/重试上限/消费一次/畸形跳过/毒丸抛错）是默认运行的纯 Mockito；KafkaOutboxIntegrationTest（:42 端到端去重）与 KafkaDltIntegrationTest（:44 重试后进 DLT）需 KAFKA_EVIDENCE=true 门控运行
- 外部源码索引（MustUnderstand）：[Redis SET NX](https://redis.io/docs/latest/commands/set/) 的租约锁边界；[MySQL transactions](https://dev.mysql.com/doc/refman/8.4/en/innodb-transactional-model.html) 的提交/回滚事实；[OutboxPublisher.java](../../app/src/main/java/com/allen/questionbank/event/OutboxPublisher.java) 第 20-36 行“为什么用原生 SQL ON DUPLICATE”的完整论证
- 官方：[Stripe Idempotency](https://docs.stripe.com/api/idempotent_requests)、[Transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html)
