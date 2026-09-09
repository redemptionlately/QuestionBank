# Day53 Redis & MQ Practice · 题目与标准解答（Solutions）

> 缓存与消息可靠性实践；对照本项目单实例 ExpiringCache 与持久化 @Async 任务。

## Current

### Q1. 写出带 TTL 的 cache-aside 读写代码和 Lua 令牌桶原子边界。
```java
// 读：GET → miss → DB → SET(EX)
String key = "published";
String raw = redis.opsForValue().get(key);
if (raw != null) return decode(raw);
List<PaperView> v = repo.loadPublished();
redis.opsForValue().set(key, encode(v), Duration.ofMinutes(2));   // TTL
return v;
// 写：先提交 DB，再删缓存（afterCommit）
redis.delete(key);
```
Lua 令牌桶（读-算-写-设 TTL 在一个脚本内原子执行）：
```lua
local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or ARGV[2])
local last = tonumber(redis.call('HGET', KEYS[1], 'ts') or 0)
local now = tonumber(ARGV[3])
tokens = math.min(ARGV[2], tokens + (now-last)*ARGV[1])
local allow = tokens >= 1 and 1 or 0
if allow==1 then tokens = tokens-1 end
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('EXPIRE', KEYS[1], ARGV[4])
return allow
```
**原子边界**：Lua 只保证 Redis 单实例执行脚本期间不被其他命令打断；它**不构成 Redis 与 MySQL 之间的全局事务**，主从切换/网络超时仍可能产生可见性差异，缓存锁不是绝对互斥。

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
- 消费者必须用**事件 id / 业务唯一键**去重，保证重复投递效果与一次相同。

---

### Q3. 写出 Outbox 表字段、业务事务与 publisher 状态转换。
```sql
CREATE TABLE outbox_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL,          -- NEW / SENT
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at TIMESTAMP(6) NULL,
  retry_count INT NOT NULL DEFAULT 0
);
```
- **业务事务**：写业务表 + 插一条 status=NEW 的 outbox（同一本地事务，原子）；
- **publisher**：轮询/订阅 NEW 事件投递 MQ，成功后置 SENT（或删除）；崩溃导致重复投递时，消费端按 idempotency_key 幂等；
- 状态转换 NEW→SENT 单向，失败 retry_count++ 退避重试。Outbox 只消除“DB 提交但消息没发”的窗口，不自动解决顺序、毒消息。

---

## External

### E1. 缓存失效/消息重复/消费者崩溃/毒消息处理路径。
- 缓存失效：DB commit 后 DEL；并发回源旧值用 afterCommit/版本化 key；穿透空值缓存、击穿 single-flight、雪崩随机 TTL；
- 消息重复：消费端幂等（唯一键去重表）；
- 消费者崩溃：未 ack 的消息重新投递（Rabbit 重回队列、Kafka 不提交 offset），处理必须可重放；
- 毒消息（永远处理失败）：限次重试后进死信，人工介入，避免无限循环阻塞分区/队列。

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
