# MustRemember

- SLI 是测量指标，SLO 是目标，SLA 是对外承诺；容量估算把峰值 QPS、请求大小和保留期映射到 CPU、内存、连接、存储和队列；演算必须标注假设，不得把假设说成实测（实测数字仅见 docs/interview/resume_one_page.md 且绑定环境）
- 治理手段与项目真实实现一一对应：限流=RateLimitFilter（本地固定窗口，默认）/RedisTokenBucketRateLimiter（Lua 令牌桶，backend=redis）；缓存=PublishedPaperCache local/Redis 双实现 + afterCommit 淘汰 + 故障降级；队列=import/general 双 @Async 池与 Kafka Outbox；重试=Outbox(5)/Kafka 消费(2 次)/ImportJob(3 次退避)；熔断=**仅 cloud/practice-service**；隔离舱=双异步池（app 无 Resilience4j）
- 模块化单体（app 9 包 + ArchUnit 5 规则约束边界、单库 11 表、本地事务）与微服务（cloud/ 6 进程：Eureka 8761/网关 8080/auth 8081/bank 8082/practice 8083、三库、Feign+Resilience4j+OTel）在项目中**两套都真实存在但不共享代码**，cloud 不并入根 Maven 聚合
- 提交接口的容量模型至少包含请求 QPS、平均/尾部耗时、数据库连接数（主库 Hikari 默认 10、read-replica 显式 5）、锁冲突（Redis 锁+FOR UPDATE 串行化同 session）、队列长度（100/200）和失败重试流量；默认限流 300/min，压测前需放宽
- 连接池容量不能简单等于线程数；数据库最大连接、每请求 SQL 数、事务持锁时间和尾部延迟共同决定可承受并发；λ_max≈P/D
- TCP 建连使用 SYN、SYN-ACK、ACK；挥手涉及 FIN/ACK，主动关闭方进入 TIME_WAIT（2MSL）；连接复用、keep-alive 减少握手与端口消耗；限流身份 remoteAddr+requestURI（RedisRateLimitFilter:51）在代理后依赖可信转发头
- DNS、TCP、TLS、HTTP、数据库是五段不同耗时；项目分层度量：MyBatisQueryMonitor（SQL 段，慢 SQL 阈值 1000ms）、ServiceLatencyAspect（Service 段，200ms warn）、Loadgen（端到端 P50/P95/P99/P999）
- Linux 用 `ss -lntp`/`lsof -i`/`curl -v`/`top`/`vmstat`；Windows 对应 netstat -ano、Test-NetConnection、curl.exe -v、Get-Process；真实反向代理：frontend/server.js（3000→8080）、cloud gateway（鉴权后注入 X-User-Id/X-User-Role，后端仍重复鉴权）
- 反向代理负责连接终止、TLS、路由和超时；应用只信任可信代理覆盖的 `X-Forwarded-*`，不能盲信客户端自带值
- 外部源码索引（MustRemember）：[Google SRE SLO](https://sre.google/workbook/implementing-slos/) 的 SLI/SLO/error budget；[HTTP semantics](https://httpwg.org/specs/rfc9110.html) 的 timeout/retry/status 语义

# MustUnderstand

- 系统设计的完整模型是问题、约束、数据流、状态机、失败模式、容量假设、观测指标、验证方式、替代方案；项目验证资产=119 测试（90 通过/29 门控跳过/0 失败）+ Loadgen + 门控集成测试
- 拆服务需要流量、团队、故障或数据所有权证据；没有证据时本地事务更简单、更容易证明正确；cloud 演示"会拆"、app 主线证明"何时不拆"
- 外部源码索引（MustUnderstand）：[Google SRE capacity planning](https://sre.google/workbook/capacity-planning/) 的容量、排队和错误预算关系
- 外部源码索引（MustUnderstand）：[RFC 9293 TCP](https://www.rfc-editor.org/rfc/rfc9293)、[RFC 9114 HTTP/3](https://www.rfc-editor.org/rfc/rfc9114) 的连接与超时语义；[curl manual](https://curl.se/docs/manpage.html) 的 verbose/timeout 选项
- 外部源码索引（MustUnderstand）：[RFC 8305 Happy Eyeballs](https://www.rfc-editor.org/rfc/rfc8305) 的 DNS/连接选择；[nginx proxy headers](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- 官方：[Google SRE Workbook](https://sre.google/workbook/table-of-contents/)
