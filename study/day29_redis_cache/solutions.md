# Day29 Redis Cache · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 cache 包（PublishedPaperCache 接口 + Local/Redis 双实现）、common/ExpiringCache、bank/BankCacheConfig、BankService 缓存用法、RedisLockService。**重构后 Redis 缓存已真实实现并由门控集成测试验证**。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[common/ExpiringCache.java:25-57](../../app/src/main/java/com/allen/questionbank/common/ExpiringCache.java)（Entry :26、get 懒惰删除 :31-36、getOrLoad 单飞+双检+顺手清扫 :37-51、evict/clear :52-53）；[bank/BankCacheConfig.java:14-20](../../app/src/main/java/com/allen/questionbank/bank/BankCacheConfig.java)（localCache Bean，TTL 硬编码 2 分钟 :18）；[cache/PublishedPaperCache.java:13-27](../../app/src/main/java/com/allen/questionbank/cache/PublishedPaperCache.java)（接口三方法 + CacheStats record）；[cache/LocalPublishedPaperCache.java:14-50](../../app/src/main/java/com/allen/questionbank/cache/LocalPublishedPaperCache.java)（@ConditionalOnProperty local/matchIfMissing 默认）；[cache/RedisPublishedPaperCache.java:29-112](../../app/src/main/java/com/allen/questionbank/cache/RedisPublishedPaperCache.java)（redis 条件实现）；`BankService.java:79/:87-98` afterCommit 淘汰、`:107` getOrLoad 回源。

---

### Q2. 说明当前单实例缓存的 key、TTL、miss 回源、evict、并发 miss 行为。
**双后端架构（重构后事实）**：BankService 只依赖 `PublishedPaperCache` 接口（BankService:25），实现由配置键 `app.cache.backend` 条件装配——`local`（缺省，LocalPublishedPaperCache:15 matchIfMissing=true）或 `redis`（RedisPublishedPaperCache:30）。
- **key**：业务侧只有一个热点 key `"published"`（已发布试卷列表）；Redis 实现加前缀 `qb:cache:`，实际键为 `qb:cache:published`（RedisPublishedPaperCache:33）；
- **TTL**：本地后端 TTL 硬编码 2 分钟（BankCacheConfig:18）；Redis 后端由 `app.cache.ttl` 注入、默认 PT2M（:46），写入时再加 0~TTL/5 的随机抖动（jitteredTtl :107-111）防雪崩；
- **get/懒惰删除**：ExpiringCache.get 发现 `expiresAt.isBefore(now)` 才 remove（:34），过期项只在再次访问时清理；
- **miss 回源**：getOrLoad 命中直接返回并计 hits；miss 执行 loader 查库，非 null 带 TTL 写回并计 misses（Local :27-35 / Redis :53-73）；
- **evict**：发布成功后注册 afterCommit 回调，事务提交后才 evict("published")（BankService:79/:87-98）；Redis 实现 evict 即 DELETE 且吞异常（:90-96）；
- **并发 miss（重要修正）**：重构后两层都做了**单飞（singleflight）**——ExpiringCache.getOrLoad 用 `synchronized(this)` + 双检（:40-43），并发 miss 只有第一个线程执行 loader（由 ExpiringCacheSingleflightTest.concurrentMissOnSameKeyLoadsExactlyOnce :23 等 6 法固化）；RedisPublishedPaperCache 用 per-key 对象锁 keyLocks + 双检（:57-61），本实例内只回源一次。**残余边界**：JVM 锁只在单实例内有效，多实例仍可能各 miss 一次（残余并发 = 实例数），由 TTL 抖动 + DB 兜底。

---

### Q3. 进程重启或缓存故障时如何回源 MySQL，为什么缓存不是事实来源。
- 本地缓存只在 JVM 堆：进程重启 map 清空 → 全 miss → 从 MySQL 重新加载，业务不丢数据；
- Redis 后端任何故障都降级回库、不让接口 500：读 GET 异常/反序列化异常 → readThrough 返回 null 并计 failures（:76-87），随后走 loader 查库；写回填失败只计 failures（:67-69）；evict 失败同样吞掉（:93-95）。门控证据 `RedisCacheIntegrationTest.cacheFallsBackToDatabaseWhenRedisIsDown`（:107，REDIS_EVIDENCE）用 mock 抛 RedisConnectionFailureException，断言返回值就是 DB 结果（assertSame）且 evict 不抛异常；
- 缓存里的已发布列表是**派生读模型**，可随时由 paper_version/question_version 重建；题库发布、答案、正式分数的权威事实只在 MySQL，缓存写失败绝不能覆盖或回滚已提交事实。

---

### Q4. 写出 cache-aside 读写流程、SETNX/INCR 原子边界和穿透/击穿/雪崩差异。
**cache-aside（本项目 Redis 实现的真实形态，RedisPublishedPaperCache:53-73）**：
```java
String redisKey = "qb:cache:" + key;
List<PaperResponse> fast = readThrough(redisKey);   // GET + 反序列化，命中计 hits
if (fast != null) return fast;
synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) { // 本实例单飞
    List<PaperResponse> recheck = readThrough(redisKey);          // 双检
    if (recheck != null) return recheck;
    List<PaperResponse> loaded = loader.get();                   // miss → DB
    redis.opsForValue().set(redisKey, mapper.writeValueAsString(loaded), jitteredTtl()); // SET EX 抖动 TTL
    return loaded;
}
```
- **写路径（本项目真实做法）**：DB 同事务提交后再 `DEL cache`（afterCommit，BankService:87-98），删除而非更新，避免并发写旧值覆盖新值；
- **SETNX**：`SET key val NX EX 10` 只在 key 不存在时成功并带过期，可实现互斥锁/单飞；**INCR** 单命令原子计数（RedisLockService:60 用它生成单调 fencing token）；“先 GET 判断再 SET”这类多步组合不是原子的，需要 Lua 脚本或 MULTI/EXEC。

**三类缓存故障**：
| 名称 | 含义 | 防护（本项目对应） |
|---|---|---|
| 穿透 penetration | 大量查询**根本不存在**的 key，每次打到 DB | 参数校验；可缓存空值（短 TTL）/布隆过滤器（本项目单热点 key，未实现布隆） |
| 击穿 breakdown | **某个热点 key** 失效瞬间大量并发回源 | 单飞锁+双检（ExpiringCache :40-50、RedisPublishedPaperCache :57-61） |
| 雪崩 avalanche | **大量 key 同一时刻**失效，DB 瞬时压力 | TTL 随机抖动 jitteredTtl（:107-111）、本地一级缓存兜底 |

---

### Q5. 写出 RedisTemplate cache-aside 代码并说明失效顺序。
本项目不是示意代码，而是真实 RedisPublishedPaperCache；等价骨架：
```java
@Component
@ConditionalOnProperty(name = "app.cache.backend", havingValue = "redis")
public class RedisPublishedPaperCache implements PublishedPaperCache {
    public List<PaperResponse> getOrLoad(String key, Supplier<List<PaperResponse>> loader) {
        String redisKey = PREFIX + key;
        List<PaperResponse> fast = readThrough(redisKey);
        if (fast != null) return fast;                       // 命中
        synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) {
            List<PaperResponse> recheck = readThrough(redisKey);
            if (recheck != null) return recheck;             // 双检
            List<PaperResponse> loaded = loader.get();       // 回源 MySQL
            try { redis.opsForValue().set(redisKey, mapper.writeValueAsString(loaded), jitteredTtl()); }
            catch (Exception writeFailure) { failures.incrementAndGet(); } // 回填失败降级
            return loaded;
        }
    }
    public void evict(String key) {
        try { redis.delete(PREFIX + key); } catch (Exception e) { failures.incrementAndGet(); }
    }
}
```
**失效顺序（重构后已改为正确顺序）**：BankService.publish 在事务内先完成状态机与 Outbox 写入（:72-78），再由 `evictPublishedAfterCommit()`（:87-98）注册 afterCommit 回调——事务同步激活时提交后才 evict（:92），无活跃事务才直接 evict（:96）。这样不会出现“事务回滚但缓存已被删/提交前被并发回源填回旧值”的窗口；门控证据 `publishEvictsCacheOnlyAfterCommit`（RedisCacheIntegrationTest:74）断言发布提交后 `qb:cache:published` 必然不存在。key/value 序列化双方一致：StringRedisTemplate + Jackson TypeReference<List<PaperResponse>>（:34）。

---

### Q6. 画出分布式锁 owner token + TTL + 释放校验。
本项目已真实实现 `redis/RedisLockService`（@ConditionalOnProperty app.lock.backend=redis，:31），三条硬规则写在类注释 :16-28：
```
加锁（tryLock :53-62）：
  key = "qb:lock:" + name；token = UUID
  SET key token NX PX <ttl>            ← setIfAbsent(key,token,ttl) 一条命令完成占锁+租约（:56）
  成功后 INCR "qb:lock:fencing"        ← 跨实例单调递增 fencing token（:60），返回 LockHandle
  失败 → Optional.empty()（调用方决定 409 或回退无锁路径）
释放（unlock :64-67，RELEASE_LUA :38-43）：
  if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
  ← Lua 保证“比对 owner token + 删除”原子，不会误删别人续上的锁；LockHandle 实现 AutoCloseable
```
- 锁必须带 TTL（进程崩溃也不会永久占锁）和唯一 owner token；释放必须 Lua 原子比对，不能裸 DEL；
- **不做 watchdog 自动续期是显式设计**（类注释 :25-28）：正确性不依赖锁长期有效——GC 停顿/时钟漂移/主从切换可能让锁提前失效，此时由数据库行锁与唯一键兜底（RedisGuardedSubmitIntegrationTest:84 绕锁实证），fencing token 供存储层拒绝旧 token 写入；
- 单实例 Redis 语义；Redlock/Redis 集群未实现，属于仅设计项，不能宣称。

---

## External

### E1. 观察 TTL / INCR。
用 `redis-cli`（compose 暴露在宿主 6380）：`SET qb:cache:k 1 EX 10` 后 `TTL qb:cache:k` 递减到 -2；对 `qb:lock:fencing` 连续 `INCR` 观察单调自增（对应 tryLock 每次成功占锁 fencing+1）。验证：过期后 GET 返回 nil（触发回源）；INCR 单线程命令原子、并发无丢失。也可直接看 RedisCacheIntegrationTest.secondPublishedReadIsServedFromRedis（:57）：第一次读后 `redis.hasKey("qb:cache:published")=true`，第二次读 hits 计数增长（需 REDIS_EVIDENCE=true）。

### E2. 设计击穿防护。
本项目已落地两级：①JVM 内单飞（synchronized + 双检，ExpiringCache/RedisPublishedPaperCache 各一份），同一实例热点 key 过期瞬间只回源一次；②TTL 抖动防雪崩。若要做到“热点永不阻塞”，可进一步：逻辑过期（值内嵌过期时间，发现逻辑过期后异步刷新、当前请求返回旧值）、SETNX 分布式单飞锁（他人回源时短暂等待后重读，跨实例只放一个回源）、本地 Caffeine 一级 + Redis 二级多级缓存。注意跨实例单飞的残余并发：N 个实例最坏 N 次回源，仍需 DB 能承受，缓存防护从不替代容量规划。
