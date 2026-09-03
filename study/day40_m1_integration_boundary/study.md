# 必须会背会写

- M1 数据流是 `file metadata -> persistent job -> text extraction -> candidate -> review -> new paper_version`
- 领域事件至少包含 `eventType`、`eventVersion`、`aggregateId`、`occurredAt`、producer 和 idempotency key
- M0 `paper_version` 是正式发布出口；导入模块只能产生候选或新草稿，不能修改已发布题目
- 当前 M0 的导入 worker 只把 `sourceName` 任务从 `RECEIVED` 推进到 `SUCCEEDED`，没有文本抽取、candidate、审核或新 `paper_version`；上面的 M1 数据流是目标设计，不是现有实现
- 事件消费者保存消费记录或业务唯一键，重复事件的效果必须与一次消费相同
- 事件 envelope 的 JSON 形态是：
  ```json
  {"eventType":"PaperDraftCreated","eventVersion":1,
   "aggregateId":"paper-123","occurredAt":"2026-08-19T00:00:00Z",
   "idempotencyKey":"evt-123","payload":{}}
  ```
- 外部源码索引（会背会写）：[Spring Modulith application events](https://docs.spring.io/spring-modulith/reference/events.html) 的 `@ApplicationModuleListener` 与事件 envelope

# 必须理解

- 跨模块不能用一个长事务强绑定；数据库状态、补偿任务和追加审计事件共同表达最终一致性
- 事件重复、乱序和消费者崩溃是默认情况；消费者需要幂等、重试、死信和可重放能力
- 外部源码索引（必须理解）：[Transactional event listeners](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) 的 BEFORE_COMMIT/AFTER_COMMIT 语义
- 官方：[Spring Modulith Events](https://docs.spring.io/spring-modulith/reference/events.html)
