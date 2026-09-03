# 必须会背会写

- [RateLimitFilter.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/common/RateLimitFilter.java) 第 17-56 行实现按远端地址和 URI 的固定窗口计数，超过容量返回 429 与 `Retry-After`；计数器使用 `ConcurrentHashMap` 和 `AtomicInteger`
- [application.yml](../../../../projects/java-question-bank-m0/src/main/resources/application.yml) 第 31-35 行提供 `app.rate-limit.capacity/window` 配置；构造函数会把小于 1 的容量和非正窗口归一化为安全默认值

- 令牌桶以 `tokens=min(capacity, tokens+rate*elapsed)` 补充并允许容量内突发；漏桶按稳定速率出队；固定窗口按窗口计数但边界可能突发
- 限流 key 可按 `userId`、IP、role、resource、route 和租户组合；key 维度决定公平性和攻击面
- 429 表示当前配额耗尽，`Retry-After` 表示秒数或日期；响应还应包含稳定错误 code
- Redis Lua 将“读取 token/计数 -> 计算 -> 写回 -> 设置 TTL”放在一个原子脚本中
- 令牌桶的计算骨架是：
  ```text
  elapsed = now - lastRefill
  tokens = min(capacity, tokens + elapsed * refillRate)
  allow = tokens >= 1
  if allow: tokens -= 1
  ```
- 外部源码索引（会背会写）：[Bucket4j](https://bucket4j.com/8.15.0/toc.html) 的 `BucketConfiguration`/`Bandwidth`；[HTTP 429](https://httpwg.org/specs/rfc6585.html#status-429) 的响应契约

# 必须理解

- 固定窗口实现简单但在窗口边界可能产生两倍突发；生产环境通常使用 Redis Lua 令牌桶/滑动窗口，并要定义集群时钟、key 维度、白名单和响应头

- 限流控制进入速率，不等价于提升数据库、CPU 或连接池容量；超时、重试和背压必须共同设计
- 单机限流只保护单实例；多实例需要共享状态或网关统一执行，时钟和网络延迟会影响精确度
- 当前过滤器以 `started + window` 判断窗口，过期项只在同一 key 再次请求时被替换；长期大量 key 会使 map 增长，因此生产实现还要有清理策略
- 窗口切换同样使用严格的 `isBefore(now)`；恰好落在边界的请求仍属于旧窗口，边界行为必须在测试和接口说明中固定
- 外部源码索引（必须理解）：[Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/) 的共享计数原子性和失败边界
- 官方：[HTTP 429](https://httpwg.org/specs/rfc6585.html#status-429)、[Bucket4j](https://bucket4j.com/)
