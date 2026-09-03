# 必须会背会写的验收

- 根据“请求超时”写出 DNS、TCP、TLS、HTTP、线程池、数据库的分层排查命令和证据。
- 写出 Dockerfile 的非 root、健康检查、信号和资源限制要点；写出 Deployment 的 readiness/liveness 配置含义。
- 画出一次 HTTPS 请求从 DNS 到 Controller 的时间线。

# 额外测试与追问

- 区分连接拒绝、连接超时、TLS 校验失败、HTTP 429、HTTP 500 和数据库锁等待。
- 设计数据库 schema 向后兼容的滚动发布与回滚步骤。
- 解释 keep-alive、HTTP/2 多路复用和连接池之间的关系。
