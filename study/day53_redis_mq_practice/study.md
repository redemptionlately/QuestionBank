# MustRemember

- Redis cache-aside 的读写顺序是 `GET -> miss -> DB -> SET(EX)` 与 `DB commit -> DEL`；缓存值可重建，数据库或领域事件才是事实来源。
- Redis Lua 将读取、计算、写回和 TTL 设置放入一次脚本执行；脚本原子不等于跨 Redis 与 MySQL 的全局事务。
- 可靠消息链包含 producer confirm、broker 持久化、consumer ack、重试和死信；至少一次投递要求消费逻辑幂等，不能假设只执行一次。
- RabbitMQ 使用 exchange、routing key、queue 和 consumer；Kafka 使用 topic、partition、offset 和 consumer group，顺序通常只在同一 partition 内成立。
- Outbox 在同一数据库事务写入业务事实和待发送事件，publisher 之后重复投递也必须安全；消费者用事件 ID 或业务唯一键去重。
- 外部源码索引（MustRemember）：[Redis Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[RabbitMQ confirms](https://www.rabbitmq.com/docs/confirms)、[Kafka consumer](https://kafka.apache.org/documentation/#consumerconfigs)、[Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html)

# MustUnderstand

- Redis 单线程命令执行只保证单个实例命令序列化；网络超时、主从切换、过期和淘汰会改变可见性，不能把缓存锁当作绝对互斥。
- producer 成功、broker 持久化、consumer 处理成功是三种不同事实；ack 过早会丢消息，ack 过晚会重复消费。
- Outbox 解决数据库提交与消息发送之间的丢失窗口，不自动解决消息顺序、重复、毒消息和 publisher 崩溃。
- 外部源码索引（MustUnderstand）：[Redis persistence and replication](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)、[Kafka delivery semantics](https://kafka.apache.org/documentation/#semantics)、[RabbitMQ consumer acknowledgements](https://www.rabbitmq.com/docs/confirms)
