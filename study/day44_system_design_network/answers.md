# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 设计十万学生提交
- 算 QPS/连接数/队列
- 写出 SLO、超时、重试、限流、缓存、队列、降级、熔断和隔离舱的作用边界。
- 用 Little 定律和连接池上限估算提交接口容量。
- 比较模块化单体和微服务的事务、网络和部署成本。
- 用 `ss`、`lsof`、`curl -v`、`top` 或 `vmstat` 设计一次 TCP/HTTP 故障排查，并解释 DNS、TLS、连接池和应用处理各自的耗时。
- 写出 DNS、TCP、TLS、HTTP、数据库五段延迟的观测点。
- 说明 keep-alive、连接复用和 TIME_WAIT 对客户端端口与服务端资源的影响。
- 写出可信反向代理设置 `X-Forwarded-For` 的前提和伪造风险。

# External

- 比较单体/模块化单体/微服务
- 写假设
- 画出 TCP 三次握手、四次挥手和 TIME_WAIT 的主动关闭方。
- 用 `ss -lntp`、`curl -v` 和 `vmstat` 解释一次端口监听、TLS/HTTP 和资源异常。
- 设计反向代理到应用的 `X-Forwarded-*` 信任边界和超时配置。
