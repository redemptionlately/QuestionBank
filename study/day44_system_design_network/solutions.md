# Day44 System Design & Network · 题目与标准解答（Solutions）

> 结合本题库提交场景的系统设计与网络排障知识；项目事实对齐：app 是 9 包模块化单体（auth/bank/cache/common/event/importjob/ops/practice/redis），另有**独立的** `cloud/` 六服务微服务演示（不与 app 共享代码、不在根 Maven 聚合内），二者正好构成单体↔微服务对照。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
系统设计完整模型九要素：**问题、约束、数据流、状态机、失败模式、容量假设、观测指标、验证方式、替代方案**。缺容量假设和失败模式的设计不算完整。项目内对应"验证方式"的真实资产：119 个 @Test（默认构建 90 通过 / 29 环境门禁跳过 / 0 失败）、开环压测器 Loadgen、`*_EVIDENCE` 门控的真中间件集成测试。

---

### Q2. 设计十万学生提交，算 QPS/连接数/队列。
**先做假设（必须显式写出）**：10 万学生集中在 2 小时完成提交，峰值是均值的 3 倍，单提交平均 50ms、每请求平均 6 条 SQL、单实例。
- 均值 QPS = 100000 / 7200s ≈ 14 QPS；峰值 ≈ 42 QPS；
- 由 Little 定律，峰值在途请求 L = λ×W = 42×0.05 ≈ 2.1；
- 每请求 6 条 SQL、每条 5ms，则每请求占用连接约 30ms，需要的 DB 并发 ≈ 42×0.03 ≈ 1.3，留余量配 10~20 连接即可（连接池不是越大越好）；项目主数据源未显式设池大小（HikariCP 默认 maximumPoolSize=10），read-replica profile 显式 maxPoolSize=5/connectionTimeout=5000ms；
- 队列：异步导入按"峰值生产速率 × 可接受积压时长"估算，项目 import 池 queue=100、CallerRunsPolicy 反压；注意应用层限流默认 capacity=300/min（[application.yml:48](../../app/src/main/resources/application.yml)），容量估算必须先确认限流阈值已按场景调整。
所有数字都标注假设，改假设重算；**不得把假设演算说成项目实测 QPS**（实测数字只存在于 docs/interview/resume_one_page.md 且绑定固定环境）。

---

### Q3. 写出 SLO/超时/重试/限流/缓存/队列/降级/熔断/隔离舱的作用边界，并对齐项目实现。
| 手段 | 控制对象 | 边界 | 本项目真实对应 |
|---|---|---|---|
| SLI/SLO/SLA | SLI 测量、SLO 内部目标、SLA 对外承诺 | 不直接提升性能 | /api/metrics 三计数 + /actuator/prometheus（Micrometer） |
| 超时 Timeout | 单次等待上限 | 不保证成功，只快速释放资源 | Hikari connectionTimeout=5000（read-replica）；cloud 网关 2s/8s、Feign 2s/3s（仅 cloud） |
| 重试 Retry | 瞬态错误 | 会放大流量，非幂等/确定性错误不能重试 | OutboxRelay 重投（上限 5）、Kafka 消费重试 2 次/200ms、ImportJob 退避重试（上限 3） |
| 限流 RateLimit | 进入速率 | 不增加系统容量 | 本地固定窗口 RateLimitFilter（默认）/ Redis Lua 令牌桶 RedisTokenBucketRateLimiter（backend=redis），超限 429+Retry-After |
| 缓存 Cache | 降低读成本 | 有一致性窗口，不是事实来源 | PublishedPaperCache 双实现（local 默认/Redis），发布 afterCommit 淘汰、Redis 挂降级回库 |
| 队列 Queue | 削峰填谷、异步解耦 | 压力后移，需消费能力兜底 | import/general 双 @Async 池；Kafka Outbox（topic qb-events） |
| 降级 Fallback | 故障时有限能力 | 是有损服务 | RedisPublishedPaperCache 读 Redis 失败回源 DB；cloud bankService fallback 快速失败 503 |
| 熔断 CB | 故障扩散范围 | 不修复下游 | **仅 cloud/practice-service**（Resilience4j bankService 5/5/50%/10s/半开 3）；app 未引入 |
| 隔离舱 Bulkhead | 资源隔离 | 单舱资源更少 | app 双异步池物理隔离（等价作用，未用 Resilience4j Bulkhead） |

---

### Q4. 用 Little 定律和连接池上限估算提交接口容量。
设连接池大小 P、每请求平均占用连接时间 D（秒），则数据库侧吞吐上限 λ_max ≈ P / D。例：P=10（项目 Hikari 默认值）、D=0.03s → λ_max ≈ 333 QPS 的理论上限（实际还受行锁、CPU、下游约束——同一 session 并发提交还会被 Redis 锁与 FOR UPDATE 串行化）。连接池容量**不等于** Tomcat 线程数：线程可在不访问 DB 时处理别的，但请求访问 DB 时必须持有连接；决定因素是 DB 最大连接数、每请求 SQL 数、事务持锁时间与尾部延迟（Loadgen 用 P95/P99 而非均值暴露尾部）。

---

### Q5. 比较模块化单体与微服务的事务/网络/部署成本（项目两套形态对照）。
| 维度 | 模块化单体（app，主线） | 微服务（cloud/ 独立演示，真实存在） |
|---|---|---|
| 调用 | 进程内方法调用；ArchUnit 强制跨域只走 Service（ArchitectureTest 5 规则） | HTTP/Feign RPC：practice→bank 经 cloud-common BankClient 契约，有序列化/超时/重试 |
| 事务 | 本地 ACID；发布同事务写 Outbox、afterCommit 淘汰缓存 | 每服务自己的本地事务；跨服务靠 Outbox 事件 + 幂等消费 + 熔断快速失败表达最终一致 |
| 部署 | 单 fat jar（根 Dockerfile 两阶段，非 root、readiness HEALTHCHECK）；k8s Deployment+HPA 1~3/CPU 70% | 6 进程：Eureka 8761、gateway 8080(WebFlux)、auth 8081、bank 8082、practice 8083，独立起停 |
| 数据 | 单库 question_bank 11 张表 | 三服务各持 qb_auth/qb_bank/qb_practice 三库，表设计不同（如 version vs version_no） |
| 观测 | Micrometer + Prometheus 端点、MDC requestId | 四服务 micrometer-tracing-bridge-otel + OTLP/HTTP→otelcol（4318） |
| 构建 | 根聚合仅 monitor-starter+app，JaCoCo 门禁 | cloud 自带聚合 POM、不并入根（避免覆盖率稀释），无 src/test、证据靠 shell 脚本拉真进程 |
没有证据时优先模块化单体；cloud 是"拆出去长什么样"的教学对照，两套不共享代码、前端只对接 app。

---

### Q6. 用 ss/lsof/curl -v/top/vmstat 设计一次 TCP/HTTP 排障，并解释五段耗时。
```bash
ss -lntp                 # 看端口是否监听、连接状态、进程
lsof -i :8080            # 看谁占用端口
curl -v https://host/api # 看 DNS 解析、TCP 建连、TLS 握手、请求/响应
top / vmstat 1           # CPU、运行队列、上下文切换、内存/swap
# Windows 对应：netstat -ano | findstr :8080、Test-NetConnection、curl.exe -v、Get-Process
```
**五段延迟（分别测量，不能把总耗时都算给应用代码）**：1. DNS 解析；2. TCP 建连（三次握手 RTT）；3. TLS 握手（额外 RTT）；4. HTTP 发送+服务端处理（应用/DB）；5. 数据库等待（连接获取、锁、SQL）。curl `-w` 输出 namelookup/connect/appconnect/starttransfer 各段；项目内分层对应：MyBatisQueryMonitor 量 SQL 段、ServiceLatencyAspect 量 Service 段（>200ms warn）、Loadgen 量端到端段。

---

### Q7. keep-alive、连接复用、TIME_WAIT 的影响。
- **三次握手**：客户端 SYN → 服务端 SYN-ACK → 客户端 ACK；
- **四次挥手**：主动方 FIN → 被动方 ACK → 被动方 FIN → 主动方 ACK；**主动关闭方**进入 `TIME_WAIT`，持续 2MSL，期间该四元组不能复用；
- 短连接高并发时主动关闭方累积大量 TIME_WAIT，占用本地端口；**keep-alive / 连接池复用**连接（HikariCP 复用 DB 连接、HttpClient 复用 TCP、Loadgen 单 HttpClient 承载全部虚拟线程请求），减少握手与 TIME_WAIT、摊薄 TLS 成本；服务端也要设空闲连接超时。注意项目限流身份是 `remoteAddr + ":" + requestURI`（RedisRateLimitFilter:51），连接经代理时 remoteAddr 是代理 IP，必须配合 Q8 的可信转发头。

---

### Q8. 反向代理 X-Forwarded-* 的信任前提与伪造风险。
`X-Forwarded-For/X-Forwarded-Proto` 是**可被客户端任意伪造**的头。只有当连接确实来自可信代理（Nginx/网关），且应用只信任代理覆盖的值、移除客户端自带同名头时，才能用它识别真实 IP/协议。项目内两个真实代理：①frontend/server.js（Node 3000 端口）把 /api 反代到 127.0.0.1:8080 并重写 host 头、上游不可达返回 502；②cloud gateway-service 是边缘网关，鉴权后向下游注入 X-User-Id/X-User-Role，后端服务仍重复做角色校验（内部网络也可绕过网关）。配置要点：代理 `proxy_set_header X-Forwarded-For $remote_addr;`，应用配置可信代理网段（Spring `server.forward-headers-strategy`）；否则攻击者可伪造 IP 绕过基于 IP 的限流/审计。

---

## External

### E1. 比较单体/模块化单体/微服务并写假设。
单体（无分包）→ 模块化单体（app：9 包 + ArchUnit 架构约束测试强制边界，同进程同库）→ 微服务（cloud：6 进程 + Eureka + 三库 + Feign/Resilience4j/OTel）。演进触发条件写清假设：团队人数、独立扩缩需求、故障爆炸半径、数据所有权边界；cloud 的存在证明"会拆"，但主线选择模块化单体证明"知道何时不拆"——假设不成立就不拆。

### E2. 画 TCP 三次握手/四次挥手/TIME_WAIT；用命令解释异常。
- 端口没监听：`ss -lntp`/`netstat -ano` 无记录 → curl Connection refused（对照：readiness 探针失败时 k8s 不摘流量而非重启，liveness 失败才重启）；
- TLS 异常：`curl -v` 在 SSL handshake 阶段报错（证书过期/链不全/SNI）；
- 资源异常：`vmstat` 的 r 列长期大于核数（CPU 排队）、si/so 非零（swap）、`top` 看进程 CPU/内存；据此区分是监听问题、TLS 问题还是资源瓶颈；压测时再用 JFR/GC 日志与 Loadgen 分位数交叉定位。
