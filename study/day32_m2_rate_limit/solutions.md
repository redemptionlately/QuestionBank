# Day32 M2 Rate Limit · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `RateLimitFilter`、`application.yml`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`RateLimitFilter` 17-56 行固定窗口计数；`application.yml` 31-35 行 capacity/window 配置。

---

### Q2. capacity=2 连续请求，验证第三次 429 及窗口过期行为。
真实实现：key = `remoteAddr + ":" + requestURI`，`clients.compute` 中：窗口不存在或 `started+window` 严格早于 now 就开新窗口计数=1，否则计数+1；`count > capacity` 即拒绝。
- capacity=2：第 1、2 次放行，第 3 次返回 **429**，响应头 `Retry-After: 60`（窗口秒数，至少 1），body `{"code":"RATE_LIMITED","message":"请求过于频繁"}`；
- 窗口过期后（`started+window.isBefore(now)`）下一次请求开新窗口、计数重置为 1，恢复放行；
- 边界：恰好在 `started+window` 等于 now 的时刻，因用严格 `isBefore`，仍算旧窗口（边界行为需在测试中固定）。

---

### Q3. 记录 429 的 Retry-After 与 code；换 URI/IP 为何计数 key 不同。
- 响应：HTTP 429 + `Retry-After`（距离窗口重置的秒数）+ 稳定业务 code `RATE_LIMITED`；
- key 由 IP 与 URI 拼接：换 URI（不同接口）或换远端地址（不同客户端）就是不同 key，各自独立计数，互不影响。这种维度实现简单，但意味着总配额按“IP×路由”分散。

---

### Q4. 设计登录/提交限流。
- **登录**：按 `IP + 用户名` 组合 key，较严配额（如 1 分钟 5 次），防爆破；失败计数更严格，超限要求验证码或冷却；
- **提交**：按 `userId + 资源` 限流，配合幂等键；写接口配额低于只读接口；
- 全局兜底按 IP，防止单实例刷接口；不同端点用不同容量/窗口，白名单（内网健康检查、可信服务）豁免。

---

### Q5. 写出令牌桶/漏桶/固定窗口规则、key 维度、429 字段。

| 算法 | 计数规则 | 特点 |
|---|---|---|
| 固定窗口（本项目） | 每个固定时间窗内计数，超容量拒绝，窗口结束清零 | 实现最简单；**窗口边界可能出现近 2 倍突发**（上窗口末尾 + 下窗口开头各打满） |
| 令牌桶 | 以固定速率补充令牌到桶（上限 capacity），请求取 1 枚，无令牌拒绝 | 允许容量内突发，平均速率受控，生产最常用 |
| 漏桶 | 请求入桶，以恒定速率流出处理 | 输出速率绝对平滑，不允许突发，适合强匀速下游 |

令牌桶伪代码：
```text
elapsed = now - lastRefill
tokens  = min(capacity, tokens + elapsed * refillRate)   // 按时间补充
allow   = tokens >= 1
if allow: tokens -= 1
```
**key 维度**：userId（最公平、按主体）、IP（覆盖匿名但易被代理/NAT 影响）、role、route、tenant，可组合。
**429 响应字段**：状态码 429、`Retry-After`（秒或 HTTP 日期）、稳定 code（RATE_LIMITED）、可选 `X-RateLimit-Limit/Remaining/Reset` 头。

---

### Q6. 写出 Redis Lua 的原子操作边界。
单机 `ConcurrentHashMap + AtomicInteger` 在多实例下各自计数、无法统一配额。Redis 方案把“读计数→计算→写回→设置 TTL”放进**一个 Lua 脚本**，Redis 单线程执行脚本期间不被打断，从而整体原子：
```lua
-- 固定窗口示例
local c = redis.call('INCR', KEYS[1])
if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
if c > tonumber(ARGV[2]) then return 0 else return 1 end
```
若拆成多条命令（先 GET 再 SET），并发下会丢计数；这就是必须用 Lua/MULTI 的原因。需同时定义集群时钟误差、key 维度、白名单和响应头。

---

## External

### E1. 比较本地 / Redis Lua / 网关限流。
| 方案 | 优点 | 局限 |
|---|---|---|
| 本地（本项目） | 零网络开销、最快 | 只保护单实例，多实例配额翻倍；map 中 key 长期增长需清理 |
| Redis Lua | 多实例共享配额、原子精确 | 依赖 Redis 可用性与一次网络往返；时钟/网络延迟影响精度 |
| 网关层（Nginx/APISIX/云 WAF） | 在应用前统一拦截、保护整个集群、可按域名/地域 | 离业务远，难按复杂业务主体（如用户等级）精细控制 |
实践常组合：网关粗粒度兜底 + Redis 业务级精细限流 + 本地热点保护。

### E2. 设计角色配额。
按 role 分配不同容量：ADMIN 较高（管理操作但低频）、STUDENT 常规、匿名最低；用 `role:userId:route` 组合 key，配置化为每角色 capacity/window；对批量导入等重接口单独更小配额。注意限流只控制**进入速率**，并不提升数据库/CPU/连接池容量，需要与超时、重试退避、背压一起设计。
