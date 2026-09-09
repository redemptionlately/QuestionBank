# MustRemember

- 分布式 ID 需要趋势递增、全局唯一、时间回拨处理和可观测性；Snowflake 常由时间戳、节点号和序列号组成，位数分配决定寿命与并发。
- 分布式锁至少要有 owner token、TTL、续租和 compare-and-delete 释放；锁只保护临界区，不替代数据库唯一键和幂等设计。
- 事务消息、Outbox、Saga 和 TCC 分别用不同方式表达跨服务一致性；每种方案都要说明补偿、重试、悬挂和空回滚边界。
- 分库分表的分片键决定路由、热点、跨分片查询和扩容成本；全局唯一键、分页、事务和数据迁移必须一起设计。
- CAP 讨论网络分区下的一致性与可用性取舍；BASE 强调最终一致，但必须给出收敛条件和用户可见状态。
- 外部源码索引（MustRemember）：[Snowflake paper](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake)、[Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)、[Saga pattern](https://microservices.io/patterns/data/saga.html)

# MustUnderstand

- Redis 锁在租约过期、进程暂停、网络分区时可能失效；关键数据仍需数据库约束、版本检查或 fencing token。
- Saga 补偿不是回滚，补偿动作也可能失败；业务必须允许中间状态并提供人工修复和审计。
- 分库分表通常是后期容量方案；过早拆分会牺牲查询和事务简单性，M0 的单库事实边界更容易验证。
- 外部源码索引（MustUnderstand）：[DDIA consistency](https://dataintensive.net/)、[MySQL XA limitations](https://dev.mysql.com/doc/refman/8.4/en/xa.html)
