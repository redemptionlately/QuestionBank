# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。
- 将 `app.rate-limit.capacity` 配为 2，连续请求同一路由，验证第三次返回 429，并说明窗口过期后的行为。
- 记录 429 响应的 `Retry-After` 和 JSON `code`；换 URI 或远端地址后说明为什么会使用不同的计数 key。

- 设计登录/提交限流
- 写 Retry-After
- 分别写出令牌桶、漏桶、固定窗口的计数规则、key 维度和 429 响应字段。
- 写出令牌桶伪代码和 Redis Lua 的原子操作边界。
- 比较 user/IP/route 三种限流 key 的公平性和绕过方式。

# External

- 比较本地/Redis Lua/网关
- 设计角色配额
