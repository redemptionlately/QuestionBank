# MustRemember

- 限流是双后端互斥装配，配置键 `app.rate-limit.backend`：local 缺省（RateLimitFilter 固定窗口，matchIfMissing=true）/ redis（RedisRateLimitFilter+RedisTokenBucketRateLimiter 令牌桶）；容量/窗口键 `app.rate-limit.capacity`（默认 300）、`app.rate-limit.window`（默认 PT1M），构造时把 <1 容量、零/负窗口归一化
- [RateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java) 第 22-76 行：key=`remoteAddr:requestURI`（:50），compute 内严格 isBefore 判新窗口（:59-62），超限 429 + Retry-After + `{"code":"RATE_LIMITED"}`（:63-71）；每 256 请求在 compute 之外惰性清扫过期窗口（:56-58，防随机 URI 内存泄漏）；计数用 ConcurrentHashMap+AtomicInteger，结果计入 RequestMetrics
- [RedisTokenBucketRateLimiter.java](../../app/src/main/java/com/allen/questionbank/redis/RedisTokenBucketRateLimiter.java) 第 19-80 行：键前缀 `qb:ratelimit:`，令牌以毫令牌整数存储（×1000，规避 Lua 浮点漂移），单 Lua 脚本原子完成 HMGET→按 delta 补充→扣 1000→HSET+PEXPIRE（:25-55）；refillPerMilli=max(1, capacity*1000/windowMillis)
- [RedisRateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/redis/RedisRateLimitFilter.java) 第 25-80 行：Redis 故障 **fail-open**（:56-59，warn 后放行，显式取舍而非静默兜底），拒绝响应与本地后端逐字一致
- 令牌桶以 `tokens=min(capacity, tokens+rate*elapsed)` 补充并允许容量内突发；漏桶按稳定速率出队（项目未实现）；固定窗口按窗口计数但边界可能近 2 倍突发
- 限流 key 可按 userId、IP、role、resource、route、租户组合；key 维度决定公平性和攻击面；429 必须带 Retry-After 与稳定错误 code
- 令牌桶的计算骨架是：
  ```text
  elapsed = now - lastRefill
  tokens = min(capacity, tokens + elapsed * refillRate)
  allow = tokens >= 1
  if allow: tokens -= 1
  ```
- 外部源码索引（MustRemember）：[Bucket4j](https://bucket4j.com/8.15.0/toc.html) 的 `BucketConfiguration`/`Bandwidth`；[HTTP 429](https://httpwg.org/specs/rfc6585.html#status-429) 的响应契约

# MustUnderstand

- 固定窗口实现简单但窗口边界可能两倍突发；Redis Lua 令牌桶/滑动窗口把“读-算-写-TTL”放进单脚本保证原子，拆成多命令会并发超发；多实例共享配额必须靠 Redis 或网关，单机 map 配额会被实例数放大
- fail-open vs fail-closed 必须按接口显式选择：本项目全局限流 fail-open（Redis 挂时放行），因为提交正确性另有 Redis 锁外层 + DB 行锁 + 唯一键兜底，短暂失保不产生错误结果；资金类接口则相反
- 限流控制进入速率，不等价于提升数据库、CPU 或连接池容量；超时、重试退避和背压必须共同设计
- 证据分层：本地 429 行为由 OpsComponentsTest:72 默认运行；Redis 锁+令牌桶 6 法（独占/TTL 自动释放/fencing 单调/恰好 capacity 拒绝/窗口后回补/共享 429）由 RedisLockAndRateLimitIntegrationTest 承载，REDIS_EVIDENCE=true 才运行；网关层未接入，cloud/gateway-service 是独立演示
- 外部源码索引（MustUnderstand）：[Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/) 的共享计数原子性和失败边界；[RedisTokenBucketRateLimiter.java](../../app/src/main/java/com/allen/questionbank/redis/RedisTokenBucketRateLimiter.java) 第 25-55 行真实脚本
- 官方：[HTTP 429](https://httpwg.org/specs/rfc6585.html#status-429)、[Bucket4j](https://bucket4j.com/)、[Redis Rate Limiting](https://redis.io/develop/clients/)
