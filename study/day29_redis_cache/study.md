# MustRemember

- 重构后缓存是双后端接口架构：`PublishedPaperCache`（cache 包，getOrLoad/evict/stats + CacheStats record）由 `app.cache.backend` 条件装配——local 缺省（LocalPublishedPaperCache，matchIfMissing=true）/ redis（RedisPublishedPaperCache）；BankService 只依赖接口（:25）
- 本地基线 `ExpiringCache<K,V>`（common 包）：读路径 `get -> 过期懒惰删除 -> miss -> synchronized(this)+双检 -> loader 一次 -> put(TTL)`，**已实现单飞**（并发同 key miss 只回源一次，ExpiringCacheSingleflightTest 6 法固化），加载时顺手清扫全表过期项（:44-46）；本地 TTL 硬编码 2 分钟（BankCacheConfig:18）
- Redis 后端真实事实：键前缀 `qb:cache:`、TTL 由 `app.cache.ttl`（默认 PT2M）注入并加 0~TTL/5 随机抖动（jitteredTtl :107-111）；per-key 对象锁+双检做实例内单飞；GET/反序列化/SET/DELETE 任何异常都吞掉并计 failures，降级回 MySQL 绝不让接口 500
- 写路径失效顺序：发布事务内改状态+写 Outbox，afterCommit 回调里才 evict("published")（BankService:79/:87-98），不是提交前淘汰；门控证据 RedisCacheIntegrationTest 三法（命中 :57、提交后淘汰 :74、宕机降级 :107，REDIS_EVIDENCE=true 才运行）
- Redis 的 string/hash/list/set/sorted set 分别适合序列化值、字段集合、队列/列表、去重集合和按 score 排序集合；通用 cache-aside 读路径是 `GET -> miss -> DB -> SET(EX)`，写路径是 `DB commit -> DEL cache`
- `SET key value NX PX ms` 可做带过期互斥（RedisLockService.tryLock :53-62 真实实现，另 INCR qb:lock:fencing 发单调 fencing token），`INCR` 单命令原子；释放锁必须 Lua 比对 token 再删（RELEASE_LUA :38-43），多命令组合需要 Lua/事务
- cache-aside 的代码骨架是：
  ```java
  String redisKey = "qb:cache:" + key;
  List<PaperResponse> cached = readThrough(redisKey);
  if (cached != null) return cached;
  synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) {
      List<PaperResponse> recheck = readThrough(redisKey);   // 双检
      if (recheck != null) return recheck;
      List<PaperResponse> loaded = loader.get();             // 回源 MySQL
      redis.opsForValue().set(redisKey, write(loaded), jitteredTtl());
      return loaded;
  }
  ```
- 外部源码索引（MustRemember）：[RedisTemplate API](https://docs.spring.io/spring-data/redis/reference/redis/template.html) 的 `opsForValue().get/set`；[Redis SET 命令](https://redis.io/docs/latest/commands/set/) 的 `NX/EX/PX` 参数

# MustUnderstand

- [ExpiringCache.java](../../app/src/main/java/com/allen/questionbank/common/ExpiringCache.java) 第 25-57 行：Entry 保存值和过期时间，get 懒惰删除，getOrLoad 单飞+双检+清扫，evict/clear 主动失效；[BankCacheConfig.java](../../app/src/main/java/com/allen/questionbank/bank/BankCacheConfig.java) 第 16-19 行把本地 TTL 配为 2 分钟（泛型绑死 PaperResponse，所以配置类归属 bank 域）
- [RedisPublishedPaperCache.java](../../app/src/main/java/com/allen/questionbank/cache/RedisPublishedPaperCache.java) 第 29-112 行：共享缓存、抖动 TTL、单飞双检、三类故障降级与 failures 计数；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 87-98 行 afterCommit 淘汰、:107 行 getOrLoad 回源
- 本地缓存只在单实例有效；Redis 共享缓存解决多实例一致性，但 JVM 单飞锁仍只在实例内（残余并发=实例数），不能把本地缓存包装成分布式缓存，也不能把 Redis 缓存当事实来源——题目发布、答案、正式分数仍以 MySQL 为准，缓存写失败不能覆盖已提交事实
- 穿透是大量不存在 key 绕过缓存，击穿是热点 key 同时失效（单飞+双检防护），雪崩是大量 key 同时失效（随机 TTL 防护）；空值缓存/布隆过滤器本项目未实现，属残余项
- 分布式锁需要 owner token、TTL 和 Lua 释放校验；本项目不做 watchdog 续期是显式设计——锁失效后由 DB 行锁+唯一键兜底；Redlock/Redis 集群仅设计未实现，不能宣称
- 外部源码索引（MustUnderstand）：[Redis 事务与脚本](https://redis.io/docs/latest/develop/using-commands/transactions/) 的 MULTI/EXEC/Lua 原子边界；[RedisLockService.java](../../app/src/main/java/com/allen/questionbank/redis/RedisLockService.java) 第 30-108 行
- 官方：[Redis Data Types](https://redis.io/docs/latest/develop/data-types/)、[Spring Data Redis](https://docs.spring.io/spring-data/redis/reference/)
