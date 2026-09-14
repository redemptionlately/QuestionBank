# Day53 Redis & MQ Practice · 题目与标准解答（Solutions）

> 缓存与消息可靠性实践；对照本项目真实实现：双实现 PublishedPaperCache（本地 ExpiringCache 默认 / RedisPublishedPaperCache 条件装配）、Redis Lua 令牌桶限流、Redis 分布式锁，以及 event 包 8 类构成的 Kafka Outbox（topic=qb-events、group=question-bank、死信 qb-events.DLT、consumed_event 幂等表）。

## Current

### Q1. 写出带 TTL 的 cache-aside 读写代码和 Lua 令牌桶原子边界。
```java
// 读：GET → miss → DB → SET(EX)
String key = "qb:cache:published";
String raw = redis.opsForValue().get(key);
if (raw != null) return decode(raw);
List<PaperResponse> v = repo.loadPublished();
redis.opsForValue().set(key, encode(v), jitteredTtl(baseTtl));   // TTL + 随机抖动防雪崩
return v;
// 写：先提交 DB，再删缓存（afterCommit 回调里 DEL，无活跃事务才直接删）
redis.delete(key);
```
本项目对应实现是 `cache/RedisPublishedPaperCache`（app.cache.backend=redis 时装配，默认是 LocalPublishedPaperCache 委托 ExpiringCache）：fast 读 → per-key singleflight（keyLocks.computeIfAbsent，防击穿）→ 双检 → 回源写入带随机抖动的 TTL（默认 PT2M）；Redis 读/反序列化异常计入 failures 并降级回库，不外溢。写路径在 BankService 发布事务的 TransactionSynchronization.afterCommit 里 evict（提交前不删，避免并发读把旧值重新填回）。
Lua 令牌桶（读-算-写在一个脚本内原子执行；本项目 RedisTokenBucketRateLimiter 用 capacity*1000 的“毫令牌”整数存储，避免浮点漂移）：
```lua
local stored = tonumber(redis.call('GET', KEYS[1]) or '0')   -- 项目用整数毫令牌
local now = tonumber(ARGV[1])
-- 按距上次的 elapsed 补充令牌，封顶 capacity；扣 1 个令牌；写回并设 PEXPIRE
local allow = (stored >= 1000) and 1 or 0
if allow == 1 then redis.call('SET', KEYS[1], stored - 1000, 'PX', ARGV[2]) end
return allow
```
**原子边界**：Lua 只保证 Redis 单实例执行脚本期间不被其他命令打断；它**不构成 Redis 与 MySQL 之间的全局事务**，主从切换/网络超时仍可能产生可见性差异，缓存锁不是绝对互斥。项目 RedisRateLimitFilter 因此显式选择 fail-open（Redis 故障放行而非全站拒绝），这个取舍必须能口头说明。

---

### Q2. 写出 RabbitMQ 与 Kafka 消息路径，标注 confirm/ack/重试/死信/幂等键。
**RabbitMQ**：
```
producer --(publisher confirm 确认到达 broker)--> exchange
  --(binding/routing key 路由)--> queue(持久化 durable)
  --> consumer 处理成功后手动 ack；失败 nack → 退避重试 → 超限进 DLX/DLQ
```
**Kafka**：
```
producer(acks=all, 副本持久化确认) → topic → partition(按 key 分区，同分区有序)
  consumer group 维护 offset：处理成功再提交(commit)；失败重试 → 死信 topic
```
- **ack 过早**（处理完之前就确认）→ 崩溃丢消息；**ack 过晚**（at-least-once）→ 重新投递、重复消费；
- 消费者必须用**事件 id / 业务唯一键**去重，保证重复投递效果与一次相同。本项目 Kafka 路径：OutboxRelay 用 `kafkaTemplate.send(topic, eventKey, envelope).get()` 同步等 ack（topic 默认 qb-events），PaperPublishedEventConsumer 以 eventKey 在 consumed_event 表做消费幂等（existsByEventKey 命中即跳过），畸形 JSON/缺 eventKey 直接 skip 不崩，合法但持续失败的毒丸抛异常交容器错误处理器（重试 2 次、间隔 200ms），最终由 DeadLetterPublishingRecoverer 投到原 topic+".DLT"（即 qb-events.DLT，分区与 key 保持不变）。

---

### Q3. 写出 Outbox 表字段、业务事务与 publisher 状态转换。
本项目真实表（[V4__outbox_events.sql](../../app/src/main/resources/db/migration/V4__outbox_events.sql)，与下面通用模型对照看）：
```sql
-- 发件箱：状态 PENDING / SENT / FAILED（不是 NEW）
CREATE TABLE outbox_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  aggregate_type VARCHAR(60)  NOT NULL,         -- 如 'paper'
  aggregate_id   BIGINT       NOT NULL,         -- 如 paperId
  event_type     VARCHAR(60)  NOT NULL,         -- 如 PAPER_PUBLISHED
  payload_json   TEXT         NOT NULL,
  status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  retry_count    INT          NOT NULL DEFAULT 0,
  last_error     VARCHAR(500) NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at        TIMESTAMP    NULL,
  UNIQUE KEY uk_outbox_dedup (aggregate_type, aggregate_id, event_type), -- 同聚合同事件只入队一次
  KEY idx_outbox_pending (status, created_at)                            -- Relay 拉取 PENDING
);
-- 消费端幂等表（至少一次投递下去重）
CREATE TABLE consumed_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(190) NOT NULL,              -- OutboxEvent.eventKey() 拼出的确定性键
  consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_consumed_event_key (event_key)
);
```
- **业务事务**：BankService.publish 在同一 @Transactional 内写业务表并调 `OutboxPublisher.enqueue`（JdbcTemplate INSERT outbox_event，序列化失败整体回滚，blank payload 拒绝，uk_outbox_dedup 兜底重复）；整套组件仅 app.kafka.enabled=true 时装配，关闭时 ObjectProvider 注入为 no-op；
- **publisher/relay**：`OutboxRelay.relayPendingEvents` 按 `${app.outbox.poll-interval-ms:1000}` 定时拉 PENDING（顺序 created_at ASC），`send(topic, eventKey, 信封JSON).get()` 同步等 ack，成功 markSent（PENDING→SENT、写 sent_at）；失败 requeue（retry_count++、last_error 截断 500），retry_count 达到 `${app.outbox.max-attempts:5}` 置 FAILED 不再自动重试；
- 状态转换 PENDING→SENT 正常路径，PENDING→（失败）PENDING 重试、累计达上限→FAILED；Outbox 只消除“DB 提交但消息没发”的窗口，不自动解决顺序、毒消息——毒消息由消费端抛出后走重试→qb-events.DLT。
- **对应测试**：OutboxTest（9，纯单元：状态机/截断/去重/序列化失败回滚）、OutboxRelayAndConsumerTest（5：投递成功、重试上限、消费幂等、畸形消息跳过、毒丸交 DLT）默认构建即跑；KafkaOutboxIntegrationTest、KafkaDltIntegrationTest 为真 Kafka 全链路，门控 KAFKA_EVIDENCE 默认跳过。

---

## External

### E1. 缓存失效/消息重复/消费者崩溃/毒消息处理路径。
- 缓存失效：DB commit 后 DEL（本项目严格放在 afterCommit 回调）；并发回源旧值用 singleflight/版本化 key；穿透空值缓存、击穿 per-key 锁（RedisPublishedPaperCache.keyLocks）、雪崩随机 TTL（jitteredTtl）；Redis 整体不可用时捕获异常降级回数据库（RedisCacheIntegrationTest 三用例分别固化命中、提交后才失效、宕机降级，门控 REDIS_EVIDENCE）；
- 消息重复：消费端幂等（本项目 consumed_event 表 + uk_consumed_event_key，existsByEventKey 命中即跳过）；
- 消费者崩溃：未 ack/offset 未提交的消息重新投递，处理必须可重放（本项目消费方法 @Transactional，落幂等记录与业务处理同事务）；
- 毒消息（永远处理失败）：容器层重试 2 次后进死信 qb-events.DLT，人工介入，避免无限循环阻塞分区；畸形 JSON/缺 key 则直接 skip，不算毒丸。

### E2. 比较 Redis Stream / RabbitMQ queue / Kafka partition。
| 维度 | Redis Stream | RabbitMQ | Kafka |
|---|---|---|---|
| 模型 | 追加流 + 消费组 | exchange→queue | topic→partition |
| 顺序 | 单 stream 内 | 单 queue 内（多消费者抢占不保证全局） | 仅同 partition 内 |
| 重放 | 按 ID 范围重读 | ACK 后默认删除，需配置 | 按 offset 任意重放，强项 |
| 扩展 | 适合轻量/已用 Redis | 路由灵活、协议丰富 | 高吞吐、日志型、生态强 |
选型：轻量事件可用 Stream，复杂路由/确认用 RabbitMQ，高吞吐可重放流用 Kafka。

### E3. 为什么“消息发送成功”不等于“业务处理完成”。
producer 收到 confirm 只代表 broker 已接收/持久化，下游 consumer 可能还没处理、处理失败或正在重试。三个不同事实：生产成功、broker 持久化、消费成功。业务完成应以消费端处理并落库（或 Saga 终态）为准；需要结果时用回调/状态查询，而不是把“已发出”当“已完成”。
