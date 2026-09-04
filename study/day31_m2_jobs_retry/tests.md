# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。
- 运行 `POST /api/import-jobs` 并轮询任务，验证 202、Location、RECEIVED、SUCCEEDED 和 progress，并从查询 JSON 读取 attempt；根据 `ImportJob` 的状态方法指出 afterCommit 的作用。
- 用另一个学生 token 查询第一个学生的任务，验证 `id + ownerId` 资源隔离；检查 V3 迁移中的外键、状态/更新时间索引和 `@Version` 字段。

- 设计 PDF 导入状态机
- 写租约恢复
- 写出任务状态、attempt、nextRunAt、leaseUntil、指数退避和死信条件。
- 写出 worker 条件 UPDATE，并解释租约过期后的接管条件。
- 写出至少一次执行下的幂等结果键。
- 画出 producer、broker、consumer ack、重试和死信的时序；比较 RabbitMQ routing key、Kafka partition/offset 和 Outbox 的事实边界。
- 写出 RabbitMQ exchange、routing key、queue、consumer 的消息路径。
- 写出 Kafka topic、partition、offset、consumer group 的消费路径。

# External

- 模拟重复 worker
- 设计人工重放
- 为消息发送失败、消费失败和重复消费分别指定 ack、重试、死信和幂等处理。
- 写出 Outbox 表的最小字段和 publisher 重复投递后的状态转换。
