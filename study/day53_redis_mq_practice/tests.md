# 必须会背会写的验收

- 写出带 TTL 的 cache-aside 读写代码和 Lua 令牌桶的原子边界。
- 写出 RabbitMQ 与 Kafka 的消息路径，标注 confirm、ack、重试、死信和幂等键。
- 写出 Outbox 表字段、业务事务和 publisher 状态转换。

# 额外测试与追问

- 设计缓存失效、消息重复、消费者崩溃和毒消息的处理路径。
- 比较 Redis Stream、RabbitMQ queue 和 Kafka partition 的顺序、重放和扩展方式。
- 说明为什么“消息发送成功”不能直接返回“业务处理完成”。
