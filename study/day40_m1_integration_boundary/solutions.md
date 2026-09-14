# Day40 M1 Integration Boundary · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实代码。**领域事件链路已真实实现**：`event/` 包 8 个类构成事务发件箱（Outbox）+ 幂等消费者 + 死信（topic `qb-events`、consumer group `question-bank`、DLT `qb-events.DLT`），并有单元/集成测试；**M1 导入候选流（文本抽取→candidate→审核→建版本）仍为目标设计**。两部分严格分开。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
已实现锚点（行号取自真实代码）：
- [event/OutboxEvent.java:19](../../app/src/main/java/com/allen/questionbank/event/OutboxEvent.java)（outbox_event 表，PENDING/SENT/FAILED 状态机，markSent :67/markFailed :72/requeue :79，eventKey() :84 = aggregateType+aggregateId+eventType 确定性键）；
- [event/OutboxPublisher.java:16-18](../../app/src/main/java/com/allen/questionbank/event/OutboxPublisher.java)（`app.kafka.enabled=true` 才装配；enqueue @Transactional :54-55，JdbcTemplate 直接 INSERT，uk_outbox_dedup 兜底，序列化失败整笔回滚）；
- [event/OutboxRelay.java:23-25](../../app/src/main/java/com/allen/questionbank/event/OutboxRelay.java)（@Scheduled 1000ms 拉 PENDING，`kafkaTemplate.send(topic,eventKey,wrap(event)).get()` 同步等 ack，成功 markSent、失败 requeue、retryCount≥maxAttempts(默认 5) 置 FAILED）；
- [event/PaperPublishedEventConsumer.java:18-20](../../app/src/main/java/com/allen/questionbank/event/PaperPublishedEventConsumer.java)（@KafkaListener topics `qb-events`、group `question-bank`，:32-34；existsByEventKey 幂等，畸形/缺 key 跳过，毒丸抛 IllegalStateException）；
- [event/KafkaConsumerConfig.java:21-23](../../app/src/main/java/com/allen/questionbank/event/KafkaConsumerConfig.java)（重试 2 次间隔 200ms，DeadLetterPublishingRecoverer 投原 topic+".DLT"、同分区、key 原样）；
- 上游：[bank/BankService.java:74-78](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 发布时同事务 enqueue("paper", paperId, "PAPER_PUBLISHED", payload)，ObjectProvider 注入使 Kafka 关闭时整体 no-op。
仍是设计态的锚点：ImportJobWorker 只把 sourceName 任务推进 RECEIVED→PROCESSING→SUCCEEDED/FAILED（+退避重试），没有文本抽取/candidate/审核/建 paper_version。

---

### Q2. 画 happy path / 失败分支。
**已实现的发布事件流（真实）**：
```
BankService.publish(本地事务)
  ├─ paper_version DRAFT→PUBLISHED 与 outbox_event(PENDING) 同一事务提交
  └─ afterCommit: 淘汰 PublishedPaperCache
OutboxRelay(每 1s,独立事务): 拉 PENDING → send(qb-events).get() 同步等 ack
  ├─ ack → markSent(SENT)
  └─ 失败 → requeue(retryCount++)；≥5 次 → FAILED(留 lastError)
Consumer(group=question-bank): 解析 envelope → existsByEventKey?
  ├─ 已消费 → 跳过（幂等）
  ├─ 畸形/缺 eventKey → skip 不崩
  └─ 毒丸 → 重试 2 次(200ms) → qb-events.DLT
```
**M1 导入候选流（目标设计，未实现）**：上传 → job RECEIVED → afterCommit worker → 文本抽取(PARSING) → candidate(CREATED→REVIEWING) → 人工 ACCEPTED → 新 DRAFT paper_version → publish。失败分支：抽取失败 job FAILED（现有退避重试最多 3 次）；审核 REJECTED 终止留痕；建版本失败候选保留可重试；任何阶段不改已发布版本。

---

### Q3. 明确"未实现不能进简历"。
重构后**可以写且有测试证据**的能力：事务发件箱 Outbox（同事务落库、Relay 同步等 ack、失败重投/达上限 FAILED）、消费端按 eventKey 幂等（consumed_event 表 + uk_consumed_event_key）、毒丸重试后进 .DLT、Kafka 关闭时发布主流程不受影响（ObjectProvider 缺省）；对应测试 OutboxTest(9)、OutboxRelayAndConsumerTest(5)、KafkaOutboxIntegrationTest/KafkaDltIntegrationTest（KAFKA_EVIDENCE 门控）。
**仍不能写**：PDF 文本抽取、candidate、人工审核流、对象存储、schema registry/事件版本治理——这些没有源码，最多写"设计了方案"。这是 verified 与 learned 的边界；门控集成测试在无环境时默认跳过，简历表述也要与"默认构建 90 通过、29 环境门禁跳过"的口径一致。

---

### Q4. 写出事件 eventType/eventVersion/aggregateId/occurredAt/幂等标识。
**真实 envelope（OutboxRelay.wrap() :71）字段**：`eventId / eventKey / eventType / occurredAt / payload`——注意真实代码**没有 eventVersion/producer 字段**，eventVersion 是下面给出的设计演进建议，不得说成现有字段。
| 字段 | 作用 | 项目对应 |
|---|---|---|
| eventType | 事件类型 | 真实值 "PAPER_PUBLISHED"（BankService:75-77） |
| eventId/幂等键 | 事件唯一标识，供消费者去重 | eventKey()=aggregateType+aggregateId+eventType（OutboxEvent:84），消费端 existsByEventKey |
| aggregateId | 所属聚合 id | paperId（Long） |
| occurredAt | 发生时间（UTC） | wrap 时生成 |
| payload | 事件数据 | BankService 组装的发布载荷 JSON |
| eventVersion（设计建议） | schema 版本，支持兼容演进 | **当前未实现** |
真实 outbox 行另有 status/retryCount/lastError 与 uk_outbox_dedup(aggregate_type,aggregate_id,event_type) 保证同聚合同事件只入队一次。
设计态 envelope JSON（含建议的 eventVersion）：
```json
{"eventId":"evt-123","eventKey":"paper:123:PAPER_PUBLISHED","eventType":"PAPER_PUBLISHED",
 "eventVersion":1,"aggregateId":"paper-123","occurredAt":"2026-09-09T00:00:00Z","payload":{}}
```

---

### Q5. 说明消费者重复、乱序、死信处理。
- **重复（至少一次投递，默认会发生）**：真实消费者处理前先 `existsByEventKey` 查 consumed_event，命中直接跳过，未命中则同事务落 consumed_event 再处理（PaperPublishedEventConsumer:34-50）——重复投递效果与消费一次相同；
- **乱序**：真实链路按 eventKey（聚合+类型）顺序由单分区键保序（send 以 eventKey 作 key，DLT 保持同分区）；跨聚合乱序靠业务状态判断；通用设计上事件应带 occurredAt/版本，过期事件不覆盖新状态（当前消费者未做版本比较，属可演进点）；
- **畸形消息**：JSON 解析失败或缺 eventKey 直接 skip，不拖崩容器（OutboxRelayAndConsumerTest.consumerSkipsMalformedAndKeylessMessagesInsteadOfCrashing :101）；
- **毒丸（合法但业务持续失败）**：抛 IllegalStateException → KafkaConsumerConfig 重试 2 次/200ms → DeadLetterPublishingRecoverer 投 `qb-events.DLT`（KafkaDltIntegrationTest :43 门控验证），不阻塞后续分区消费；人工修正后可重放。

---

### Q6. 画出 M0 发布出口与 M1 导入候选之间的事务边界。
```
[发布事务(已实现)]                [Relay 事务(已实现)]         [消费事务(已实现)]
BankService.publish     ──同库──▶ OutboxRelay 拉 PENDING ──▶ Consumer 落 consumed_event
paper_version 翻转 +               send().get() 等 ack          + 处理副作用，幂等
outbox_event(PENDING)              markSent/requeue/FAILED      毒丸→重试→.DLT
（同一本地事务；afterCommit 才 evict 缓存）

[导入事务(设计)]                   [审核事务(设计)]             [题库事务(已实现出口)]
job/candidate 推进      ──事件──▶  审核决定(追加记录) ──事件──▶ 创建 DRAFT → publish
```
- 跨模块**不用分布式长事务**强绑定：各自本地事务 + Outbox 领域事件 + 补偿表达最终一致；项目已用真实代码验证前半段（发布→Outbox→消费）；
- `paper_version` 是唯一正式发布出口：导入模块未来只能产出 candidate 或新 DRAFT，绝不直接改已发布题面；
- "业务与事件同事务落 outbox、提交后再投递"已落地（BankService:74-77），从根本上避免"业务回滚但事件已发"。

---

## External

### E1. 设计事件 schema/version。
每个 eventType 定义带版本的 schema（字段、类型、必填、兼容规则）：新增可空字段为兼容变更，改语义/删字段为破坏性变更需升 eventVersion；消费者按 version 反序列化，老消费者遇到未知高版本安全忽略或进 DLT，不得崩溃（真实消费者对畸形消息 skip 不崩已是第一层防线）。Schema 集中登记并随代码评审；当前 envelope 无 eventVersion 字段，落地时在 wrap() 与 OutboxEvent 表同步增加。

### E2. 模拟重复乱序。
项目已有可直接运行的对应用例（纯单元，无需 Kafka 环境）：OutboxRelayAndConsumerTest 中 consumerRecordsEventOnceThenSkipsDuplicates（:84，同 eventId 两次只一次副作用）、consumerSkipsMalformedAndKeylessMessagesInsteadOfCrashing（:101）、consumerThrowsOnPoisonMessageSoErrorHandlerCanSendItToDLT（:114，毒丸交错误处理器）；真 Kafka 全链路用 KafkaOutboxIntegrationTest（:41）/KafkaDltIntegrationTest（:43），设 KAFKA_EVIDENCE=true 运行。乱序 v1/v2/v3 的版本比较在当前代码中未实现，属于设计练习。
