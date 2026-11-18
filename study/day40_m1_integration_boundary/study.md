# MustRemember

- **事件链路已实现**：[OutboxEvent.java](../../app/src/main/java/com/allen/questionbank/event/OutboxEvent.java)（PENDING/SENT/FAILED，eventKey=aggregateType+aggregateId+eventType）、[OutboxPublisher.java](../../app/src/main/java/com/allen/questionbank/event/OutboxPublisher.java)（同事务 INSERT，uk_outbox_dedup 去重）、[OutboxRelay.java](../../app/src/main/java/com/allen/questionbank/event/OutboxRelay.java)（1s 轮询、send().get() 同步等 ack、max-attempts=5）、[PaperPublishedEventConsumer.java](../../app/src/main/java/com/allen/questionbank/event/PaperPublishedEventConsumer.java)（group=question-bank、existsByEventKey 幂等、畸形跳过、毒丸抛出）、[KafkaConsumerConfig.java](../../app/src/main/java/com/allen/questionbank/event/KafkaConsumerConfig.java)（重试 2 次/200ms、DLT=原 topic+".DLT" 同分区同 key）
- topic=`qb-events`（app.outbox.topic 默认）、consumer group=`question-bank`（app.kafka.group-id 默认）、DLT=`qb-events.DLT`；总开关 app.kafka.enabled=true 才装配四组件，BankService 用 ObjectProvider 缺省，关闭 Kafka 不阻塞发布
- 真实 envelope（OutboxRelay.wrap :71）= eventId/eventKey/eventType/occurredAt/payload，**无 eventVersion 字段（版本治理是设计态）**；上游事件在 [BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) :74-78 同事务 enqueue PAPER_PUBLISHED，:87-96 afterCommit 淘汰缓存
- 测试证据：OutboxTest 9 例、OutboxRelayAndConsumerTest 5 例（默认构建运行）；KafkaOutboxIntegrationTest/KafkaDltIntegrationTest 为 KAFKA_EVIDENCE 门控（默认跳过）
- M1 数据流 `file metadata -> persistent job -> text extraction -> candidate -> review -> new paper_version` 仍是**目标设计**：导入 worker 只推进 sourceName 任务（RECEIVED/PROCESSING/SUCCEEDED/FAILED + 退避重试），无文本抽取、candidate、审核
- M0 `paper_version`（真实 DRAFT/PUBLISHED）是唯一正式发布出口；导入模块只能产出候选或新草稿，不能修改已发布题目
- 事件消费者保存消费记录（consumed_event + uk_consumed_event_key）或业务唯一键，重复事件的效果必须与一次消费相同
- 外部源码索引（MustRemember）：[Spring Modulith application events](https://docs.spring.io/spring-modulith/reference/events.html) 的 `@ApplicationModuleListener` 与事件 envelope（项目未用 Modulith，用手写 Outbox）

# MustUnderstand

- 跨模块不能用一个长事务强绑定；数据库状态、补偿任务和追加事件共同表达最终一致；项目已落地的形态是"本地事务同写业务行+outbox 行 → 调度 Relay 异步投递 → 消费端幂等表去重 → 毒丸进 DLT"
- 事件重复、乱序和消费者崩溃是默认情况：重复已由 consumed_event 解决；乱序版本比较当前未实现（设计点）；毒丸→重试→DLT 已实现；消费者必须可重放
- verified/learned 边界：Outbox+幂等消费+DLT 有源码有测试可写简历；PDF 导入候选流、事件 schema 版本化无源码，只能写"设计"
- 外部源码索引（MustUnderstand）：[Transactional event listeners](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) 的 BEFORE_COMMIT/AFTER_COMMIT 语义（项目以 TransactionSynchronization afterCommit 手写实现）
- 官方：[Spring Modulith Events](https://docs.spring.io/spring-modulith/reference/events.html)
