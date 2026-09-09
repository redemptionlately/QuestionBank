# MustRemember

- SLI 是测量指标，SLO 是目标，SLA 是对外承诺；容量估算把峰值 QPS、请求大小和保留期映射到 CPU、内存、连接、存储和队列
- 超时控制等待，重试控制瞬态错误但会重复，限流控制进入速率，缓存降低读成本，队列削峰，降级限制功能，熔断和隔离舱限制故障范围
- 模块化单体保留进程内调用和本地事务；微服务增加网络、序列化、独立部署、分布式一致性和运维边界
- 提交接口的容量模型至少包含请求 QPS、平均/尾部耗时、数据库连接数、锁冲突、队列长度和失败重试流量
- 连接池容量不能简单等于线程数；数据库最大连接、每请求 SQL 数、事务持锁时间和尾部延迟共同决定可承受并发
- TCP 建连使用 SYN、SYN-ACK、ACK；挥手涉及 FIN/ACK，主动关闭方可能进入 TIME_WAIT；连接复用、keep-alive 和超时会改变端口、握手和资源成本
- DNS 解析、TCP 建连、TLS 握手、HTTP 请求和数据库等待属于不同耗时段；排障要分别测量，不能把总耗时直接归因于应用代码
- Linux 后端排障至少会用 `ss -lntp` 看监听和连接、`lsof -i` 看进程占用、`curl -v` 看 HTTP/TLS、`top`/`vmstat` 看资源、`journalctl` 看服务日志
- 反向代理负责连接终止、TLS、路由和超时；应用仍需校验 `X-Forwarded-*` 的可信边界，不能盲信客户端伪造的真实 IP
- 外部源码索引（MustRemember）：[Google SRE SLO](https://sre.google/workbook/implementing-slos/) 的 SLI/SLO/error budget；[HTTP semantics](https://httpwg.org/specs/rfc9110.html) 的 timeout/retry/status 语义

# MustUnderstand

- 系统设计的完整模型是问题、约束、数据流、状态机、失败模式、容量假设、观测指标、验证方式和替代方案
- 拆服务需要流量、团队、故障或数据所有权证据；没有证据时本地事务更简单、更容易证明正确
- 外部源码索引（MustUnderstand）：[Google SRE capacity planning](https://sre.google/workbook/capacity-planning/) 的容量、排队和错误预算关系
- 外部源码索引（MustRemember）：[RFC 9293 TCP](https://www.rfc-editor.org/rfc/rfc9293)、[RFC 9114 HTTP/3](https://www.rfc-editor.org/rfc/rfc9114) 的连接与超时语义；[curl manual](https://curl.se/docs/manpage.html) 的 verbose/timeout 选项
- 外部源码索引（MustUnderstand）：[RFC 8305 Happy Eyeballs](https://www.rfc-editor.org/rfc/rfc8305) 的 DNS/连接选择；[nginx proxy headers](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- 官方：[Google SRE Workbook](https://sre.google/workbook/table-of-contents/)
