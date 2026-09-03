# 必须会背会写

- 本项目先用 `ExpiringCache<K,V>` 建立 cache-aside 基线：读请求 `get -> miss -> loader -> put(TTL)`，写请求更新发布版本后淘汰 key；缓存只保存可重建读模型，不能作为题库发布和判分事实来源
- `ConcurrentHashMap.compute` 只保证一次 map 更新的原子性；`getOrLoad` 不是 single-flight，同一 key 的并发 miss 仍可能重复查询数据库。缓存值必须设置 TTL、最大容量或主动淘汰，否则过期数据和内存增长会变成新的故障源
- `ExpiringCache.get` 采用 `expiresAt.isBefore(Instant.now())` 判断过期，因此懒惰删除发生在当前时间严格晚于过期时刻；TTL 是近似有效期，不是精确到请求边界的定时删除

- Redis 的 string/hash/list/set/sorted set 分别适合序列化值、字段集合、队列/列表、去重集合和按 score 排序集合；Stream 适合追加消息流
- 通用 cache-aside 读路径是 `GET -> miss -> DB -> SET(EX)`；推荐写路径是 `DB commit -> DEL cache`，缓存不是事实来源（项目当前实现及其提交前淘汰边界见下半部分）
- `SET key value NX EX seconds` 可做带过期互斥，`INCR` 是单命令原子计数；检查再写等多命令组合需要 Lua/事务
- Spring Data Redis 的 `RedisTemplate` 负责序列化和命令访问，key/value serializer 必须与读取方一致
- cache-aside 的代码骨架是：
  ```java
  String key = "paper:" + paperId;
  PaperView cached = redis.opsForValue().get(key);
  if (cached != null) return cached;
  PaperView loaded = repository.loadView(paperId);
  redis.opsForValue().set(key, loaded, Duration.ofMinutes(5));
  return loaded;
  ```
- 外部源码索引（会背会写）：[RedisTemplate API](https://docs.spring.io/spring-data/redis/reference/redis/template.html) 的 `opsForValue().get/set`；[Redis SET 命令](https://redis.io/docs/latest/commands/set/) 的 `NX/EX` 参数

# 必须理解

- [ExpiringCache.java](../../src/main/java/com/allen/questionbank/common/ExpiringCache.java) 第 8-29 行：`Entry` 保存值和过期时间，`get` 懒惰删除过期项，`getOrLoad` 回源后写入 TTL，`evict/clear` 主动失效；[CacheConfig.java](../../src/main/java/com/allen/questionbank/common/CacheConfig.java) 第 9-12 行把 TTL 配为 2 分钟
- [BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第 55-72 行：发布保存后淘汰 `published`，查询通过 `getOrLoad` 回源。当前淘汰发生在方法事务提交前；事务回滚时淘汰是保守的，但并发回源可能再次填入旧值，生产实现应把失效动作注册到 after-commit 或使用版本化 key
- 本地缓存只在单实例有效；多实例需要 Redis 等共享缓存，并补充 key 版本、序列化、网络超时、缓存击穿保护和失效一致性。不能把本地缓存包装成分布式缓存

- 缓存不是事实来源，题目发布、答案和正式分数仍以 MySQL 为准；缓存写失败不能覆盖已提交事实
- 穿透是大量不存在 key 绕过缓存，击穿是热点 key 同时失效，雪崩是大量 key 同时失效；空值、single-flight、随机 TTL 的防护对象不同
- 分布式锁需要 owner token 和过期时间，不能只依赖 `DEL key`；网络分区下锁的安全性与租约语义必须单独分析
- 外部源码索引（必须理解）：[Redis 事务与脚本](https://redis.io/docs/latest/develop/using-commands/transactions/) 的 MULTI/EXEC/Lua 原子边界
- 官方：[Redis Data Types](https://redis.io/docs/latest/develop/data-types/)、[Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
