# Day29 Redis Cache · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `ExpiringCache`、`CacheConfig`、`BankService` 缓存用法。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`ExpiringCache` 14-29 行 Entry/懒惰删除/getOrLoad/evict；`CacheConfig` TTL=2 分钟；`BankService` 56-72 行发布后淘汰 `published`、查询 getOrLoad 回源。

---

### Q2. 说明当前单实例缓存的 key、TTL、miss 回源、evict、并发 miss 行为。
真实实现 `ExpiringCache<K,V>`：
- **key**：如 `"published"`（已发布列表），value 是可重建读模型；
- **TTL**：CacheConfig 配置为 2 分钟，写入时 `expiresAt = now + ttl`；
- **get**：`entry.expiresAt().isBefore(now)` 判定过期并懒惰 `remove(key, entry)` 返回 null——过期项只在再次访问时清理，不是后台定时删除；
- **miss 回源**：`getOrLoad(key, loader)`：get 命中直接返回；miss 执行 loader 查库，非 null 则带 TTL 写回；
- **evict**：发布成功后主动 `evict("published")`，下次查询重建；
- **并发 miss**：`ConcurrentHashMap.compute` 只保证单次 map 更新原子，`getOrLoad` **不是 single-flight**，并发 miss 时多个线程可能各自查库并重复 put（结果一致但有重复回源）。

---

### Q3. 进程重启或缓存故障时如何回源 MySQL，为什么缓存不是事实来源。
本地缓存只存于 JVM 堆：进程重启 map 清空 → 全部 miss → 从 MySQL 重新加载，业务不丢数据；缓存写入/读取异常时也应 try/catch 降级直查数据库。因为缓存里的已发布列表、视图都是**派生读模型**，可随时由题库/版本表重建；题库发布、答案、正式分数的权威事实只在 MySQL，缓存写失败绝不能覆盖或回滚已提交事实。

---

### Q4. 写出 cache-aside 读写流程、SETNX/INCR 原子边界和穿透/击穿/雪崩差异。
**cache-aside（Redis 版）**：
```java
String key = "paper:" + paperId;
PaperView v = redis.opsForValue().get(key);     // GET
if (v != null) return v;                         // hit
v = repository.loadView(paperId);               // miss → DB
redis.opsForValue().set(key, v, Duration.ofMinutes(5)); // SET EX
return v;
```
- **写路径推荐**：先 `DB commit`，再 `DEL cache`（删除而非更新，避免并发写导致旧值覆盖新值）；
- **SETNX**：`SET key val NX EX 10` 只在 key 不存在时成功并带过期，可实现互斥锁/单飞；**INCR** 单命令原子计数；但“先 GET 判断再 SET”这类多步组合不是原子的，需要 Lua 脚本或 MULTI/EXEC 保证整体原子。

**三类缓存故障**：
| 名称 | 含义 | 防护 |
|---|---|---|
| 穿透 penetration | 大量查询**根本不存在**的 key，每次都打到 DB | 缓存空值（短 TTL）/布隆过滤器/参数校验 |
| 击穿 breakdown | **某个热点 key** 失效瞬间大量并发回源 | single-flight/SETNX 互斥锁，只放一个回源 |
| 雪崩 avalanche | **大量 key 同一时刻**失效，DB 瞬时压力 | TTL 加随机抖动、分批预热、多级缓存 |

---

### Q5. 写出 RedisTemplate cache-aside 代码并说明失效顺序。
```java
@Service
class PaperViewService {
    private final StringRedisTemplate redis; private final PaperVersionRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public PaperView getPaper(Long id) throws Exception {
        String key = "paper:" + id;
        String raw = redis.opsForValue().get(key);
        if (raw != null) return mapper.readValue(raw, PaperView.class);
        PaperView view = repo.findViewById(id);            // 回源
        redis.opsForValue().set(key, mapper.writeValueAsString(view), Duration.ofMinutes(5));
        return view;
    }
    @Transactional
    public void publish(Long id) {
        // ...DB 内状态机提交...
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { redis.delete("paper:" + id); } // 提交后再删缓存
        });
    }
}
```
**失效顺序要点**：本项目当前 evict 发生在事务提交**前**（方法内 save 之后），事务回滚时这次删除是“保守”的（下次回源即可），但并发回源可能在旧事务提交前又填回旧值；生产推荐注册 `afterCommit`，在事务真正提交后再 DEL，或使用版本化 key。key/value 的序列化器读写双方必须一致。

---

### Q6. 画出分布式锁 owner token + TTL + 释放校验。
```
加锁：SET lock:job123 <唯一ownerToken> NX EX 30        ← 只在不存在时占锁并带租约
续命（可选）：Lua 校验 owner 匹配才 PEXPIRE 续期
释放：Lua 脚本：if GET==ownerToken then DEL end        ← 防止误删别人的锁
```
释放必须用 Lua 保证“比较 owner + 删除”原子；不能只 `DEL`（可能删掉已过期、被他人重新持有的锁）。网络分区下要分析租约到期后双持有者问题（Fencing token / Redlock 需单独论证）。

---

## External

### E1. 观察 TTL / INCR。
用 `redis-cli`：`SET k 1 EX 10` 后 `TTL k` 从 10 递减到 -2；`INCR k` 观察原子自增。验证：过期后 GET 返回 nil（触发回源）；INCR 在并发下无丢失（单线程命令原子）。

### E2. 设计击穿防护。
热点 key 失效时只允许一个线程回源：
```java
String lock = "lock:paper:" + id;
boolean got = redis.opsForValue().setIfAbsent(lock, token, Duration.ofSeconds(10));
if (!got) { Thread.sleep(50); return getPaper(id); }   // 他人回源中，短暂等待后重读
try { PaperView v = repo.findViewById(id); redis.set(key, v, ttl); return v; }
finally { 释放锁(Lua 校验 token); }
```
配合逻辑过期/永不过期 + 异步刷新可做到热点不阻塞；本地 Caffeine 一级缓存 + Redis 二级缓存可进一步降低延迟。
