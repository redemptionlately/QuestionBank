# Day32 M2 Rate Limit · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实双后端限流：本地固定窗口 `common/RateLimitFilter`（默认）与 Redis 令牌桶 `redis/RedisRateLimitFilter` + `redis/RedisTokenBucketRateLimiter`（重构后已真实实现）、[application.yml](../../app/src/main/resources/application.yml)。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[common/RateLimitFilter.java:22-76](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java)（@ConditionalOnProperty local/matchIfMissing 默认 :23、key :50、每 256 请求惰性清扫 :56-58、窗口 compute :59-62、拒绝 :63-71）；[redis/RedisTokenBucketRateLimiter.java:23-88](../../app/src/main/java/com/allen/questionbank/redis/RedisTokenBucketRateLimiter.java)（毫令牌 Lua :29-61、tryAcquire :71-88）；[redis/RedisRateLimitFilter.java:25-80](../../app/src/main/java/com/allen/questionbank/redis/RedisRateLimitFilter.java)（redis 条件 :26、fail-open :56-59、拒绝 :61-69）；[application.yml](../../app/src/main/resources/application.yml) app.rate-limit.capacity=300/window=PT1M。两个 Filter 由同一配置键 `app.rate-limit.backend`（local 缺省 / redis）互斥装配。

---

### Q2. capacity=2 连续请求，验证第三次 429 及窗口过期行为。
**本地固定窗口（默认后端，RateLimitFilter:59-62）**：key=`remoteAddr + ":" + requestURI`；`clients.compute` 中窗口不存在或 `started+window` 严格早于 now（isBefore）就开新窗口计数=1，否则 incrementAndGet；`count > capacity` 即拒绝（:63）。
- capacity=2：第 1、2 次放行，第 3 次返回 **429**，`Retry-After: max(1, window 秒数)`（:65），body `{"code":"RATE_LIMITED","message":"请求过于频繁"}`（:67）；
- 窗口过期后下一次请求开新窗口、计数重置为 1，恢复放行；
- 边界：`started+window` 恰好等于 now 时因严格 isBefore 仍算旧窗口（:60）；
- 内存防护：每处理 256 个请求做一次全表惰性清扫（SWEEP_EVERY :29/:56-58），清理判据与 compute 完全一致，防随机 URI 造 key 导致内存泄漏；清扫必须在 compute 之外做（ConcurrentHashMap.compute lambda 内禁止改其他映射）。
**Redis 令牌桶后端**：capacity=2 时桶内初始 2000 毫令牌，前两次各扣 1000，第三次不足 1000 → 429；之后按 refillPerMilli 随时间回补（tokenBucketAdmitsExactlyCapacityThenRejects :93、tokenBucketRefillsAfterWindowElapses :107，门控测试固化）。

---

### Q3. 记录 429 的 Retry-After 与 code；换 URI/IP 为何计数 key 不同。
- 响应：HTTP 429 + `Retry-After`（本地为窗口秒数；Redis 后端同样取 window 秒数，RedisRateLimitFilter:63）+ 稳定业务 code `RATE_LIMITED`；两种后端响应格式逐字一致；
- key 由远端地址与 URI 拼接（本地 :50 / Redis :51，Redis 实际键再加前缀 `qb:ratelimit:`，RedisTokenBucketRateLimiter:23/:73）：换 URI 或换远端地址就是不同 key，各自独立计数；配额按“IP×路由”分散。限流结果同时计入 RequestMetrics（request/failure/latency）。

---

### Q4. 设计登录/提交限流。
- **登录**：按 `IP + 用户名` 组合 key、较严配额，防爆破；超限要求验证码或冷却（当前项目登录端点走默认全局过滤器，专门的登录限流属设计项）；
- **提交**：项目已有两层真实保护——外层 Redis 锁守卫（RedisGuardedPracticeSubmitter，抢锁失败 409 SUBMIT_IN_PROGRESS）+ 本过滤器按 IP×路由 429；精细到 `userId+资源` 的提交配额仍属设计；
- 全局兜底按 IP；不同端点用不同 capacity/window（配置注入，构造函数把 <1 的容量、零/负窗口归一化为安全默认，本地 :40-41 / Redis :42-43）；白名单（actuator 探针等）需要在过滤器内显式短路——当前过滤器对全部请求计数，若豁免探针要自行加判断。

---

### Q5. 写出令牌桶/漏桶/固定窗口规则、key 维度、429 字段。

| 算法 | 计数规则 | 项目对应 |
|---|---|---|
| 固定窗口 | 每个固定时间窗内计数，超容量拒绝，窗口结束清零；实现最简单，**窗口边界可能近 2 倍突发** | RateLimitFilter（默认后端） |
| 令牌桶 | 以固定速率补充令牌到桶（上限 capacity），请求扣 1 枚，无令牌拒绝；允许容量内突发、平均速率受控 | RedisTokenBucketRateLimiter（redis 后端，已实现） |
| 漏桶 | 请求入桶，以恒定速率流出；输出绝对平滑、不允许突发 | **未实现**，适合强匀速下游的设计备选 |

**真实令牌桶算法（RedisTokenBucketRateLimiter，整数毫令牌防浮点漂移）**：
```text
capacityMilli = capacity * 1000                       -- 1 令牌 = 1000 毫令牌
refillPerMilli = max(1, capacityMilli / windowMillis) -- 每毫秒补充的毫令牌（至少 1，防长窗口取整为 0）
Lua 内原子完成：
  HMGET 桶(t, ts)；不存在则 tokens=capacityMilli, last=now
  delta = now - last；delta>0 时 tokens = min(capacityMilli, tokens + delta*refillPerMilli)
  tokens >= 1000 ? 扣 1000、allowed=1 : allowed=0
  HSET 回写 + PEXPIRE 设 TTL（闲置桶自动消失）
```
**key 维度**：userId（最公平）、IP（覆盖匿名但受 NAT/代理影响）、role、route、tenant，可组合；本项目实现 IP×route。
**429 字段**：状态码 429、`Retry-After`、稳定 code（RATE_LIMITED），可选 X-RateLimit-Limit/Remaining/Reset（本项目未实现这些自定义头）。

---

### Q6. 写出 Redis Lua 的原子操作边界。
多实例下本地 ConcurrentHashMap 各自计数、配额被实例数放大；Redis 方案把“读桶→按时间补充→扣减→写回→设 TTL”放进**一个 Lua 脚本**（RedisTokenBucketRateLimiter:25-55 真实脚本），Redis 单线程执行脚本期间不被打断，整体原子：
```lua
local data = redis.call('HMGET', key, 't', 'ts')
local tokens = tonumber(data[1]); local last = tonumber(data[2])
if tokens == nil then tokens = capacity; last = now end
local delta = now - last
if delta > 0 then tokens = math.min(capacity, tokens + delta * refill); last = now end
local allowed = 0
if tokens >= 1000 then tokens = tokens - 1000; allowed = 1 end
redis.call('HSET', key, 't', tostring(math.floor(tokens)), 'ts', tostring(last))
redis.call('PEXPIRE', key, ttl)
return allowed
```
拆成多条命令（先 HMGET 再 HSET）并发下会超发，这就是必须 Lua/MULTI 的原因。工程细节：①用毫令牌整数运算规避 Lua 浮点精度漂移（类注释 :15）；②**fail-open 是显式取舍**（RedisRateLimitFilter:56-59）：Redis 故障时本次放行并 warn，而不是拒绝全部流量——限流是保护机制，不能把依赖故障变成全站事故，代价是短暂失去限流保护；③仍需定义 key 维度、白名单与响应头。

---

## External

### E1. 比较本地 / Redis Lua / 网关限流。
| 方案 | 优点 | 局限 | 项目状态 |
|---|---|---|---|
| 本地固定窗口 | 零网络开销、最快 | 只保护单实例，多实例配额翻倍；key 增长需清扫（已做 256 次惰性清扫） | **已实现，默认** |
| Redis Lua 令牌桶 | 多实例共享配额、原子精确、允许容量内突发 | 依赖 Redis 与一次网络往返；本项目 fail-open，Redis 挂时短暂失保 | **已实现**（backend=redis，6 个门控测试，REDIS_EVIDENCE） |
| 网关层（Nginx/APISIX/云 WAF） | 应用前统一拦截、保护整个集群 | 离业务远，难按用户等级等复杂主体精细控制 | 未接入，cloud/gateway-service 是独立微服务演示，与 app 不共享 |
实践常组合：网关粗粒度兜底 + Redis 业务级精细限流 + 本地热点保护。门控证据测试：lockIsExclusiveAndCanOnlyBeReleasedByItsHolder（:47）、lockExpiresAutomaticallyAfterTtl（:67）、fencingTokenIsMonotonicallyIncreasingAcrossAcquisitions（:77）、tokenBucketAdmitsExactlyCapacityThenRejects（:93）、tokenBucketRefillsAfterWindowElapses（:107）、sharedRateLimitReturns429OnceCapacityIsExhausted（:117）；本地侧 OpsComponentsTest.rateLimitFilterReturns429AndRetryAfter（:72）默认运行。

### E2. 设计角色配额。
按 role 分配不同容量：ADMIN 较高但低频、STUDENT 常规、匿名最低；用 `role:userId:route` 组合 key，配置化为每角色 capacity/window（@Value 注入已具备切换基础，需要补角色解析——过滤器在 Security 过滤链附近，可从 SecurityContext 取 AuthPrincipal）；批量导入等重接口单独更小配额。注意限流只控制**进入速率**，并不提升数据库/CPU/连接池容量，需要与超时、重试退避（对照 ImportJobRetrySweeper 30s 指数退避）、背压一起设计；fail-open/fail-closed 的选择必须按接口风险显式决定（本项目全局限流 fail-open，提交正确性另有 DB 锁与唯一键兜底，所以 fail-open 不破坏正确性）。
