# Day61 Backend Stack Review · 题目与标准解答（Solutions）

> 全栈总复习收口；每个组件用“五件事”检验，项目只讲可证明事实。

## Current

### Q1. 选一个真实接口做端到端口述与白纸设计（以提交接口为例）。
从外到内完整讲：
1. **HTTP**：`POST /api/practices/{id}/submit`，Bearer 认证 + Idempotency-Key，请求体答案 JSON；
2. **Spring 链**：RequestIdFilter（@Order(HIGHEST_PRECEDENCE)，生成/透传 X-Request-Id、写 MDC）→SecurityFilterChain 内 ApiTokenFilter（写 SecurityContext）→授权→限流过滤器（本地固定窗口默认，可切 Redis 令牌桶）→DispatcherServlet 参数绑定与 @Valid→Controller→@PreAuthorize（类级 STUDENT）；
3. **事务**：PracticeService.submit 开事务，`findByIdForUpdate`（@Lock(PESSIMISTIC_WRITE)）加行锁，校验归属/状态，canonical 判分，写 Item/Wrong/Session，提交时统一 commit（失败整体回滚）；app.lock.backend=redis 时外层还有 RedisGuardedPracticeSubmitter 的 Redis 锁（事务外获取、提交后释放）；
4. **MySQL**：JPA 生成 SQL，uk_submission_question 联合唯一键兜底，行锁在提交/回滚时释放，EXPLAIN 验证索引（真库证据门控 MYSQL_EVIDENCE）；
5. **缓存/队列边界**：已发布列表读 PublishedPaperCache（本地 ExpiringCache 默认，app.cache.backend=redis 可切 RedisPublishedPaperCache，afterCommit 失效、可重建，事实仍在 DB）；异步导入走持久化 import_job 表 + @Async 双池 + ImportJobRetrySweeper 退避重试；发布事件经 Kafka Outbox（app.kafka.enabled=true 时装配，topic qb-events、consumed_event 幂等、.DLT）；
6. **日志指标**：requestId 贯穿（logback pattern [%X{requestId:-}]）、RequestMetrics 原子计数 + /api/metrics、micrometer-prometheus 的 /actuator/prometheus、ServiceLatencyAspect 慢调用 warn、DatabaseLatencyHealthIndicator 探针、统一 ErrorResponse(code,message,requestId,timestamp)。
白纸画出时序图与状态机，并标注每步失败返回什么（400/401/403/404/409/500）。

---

### Q2. 从 Day50-60 各抽一题限时作答并标注不会。
按域各一道（示例，要求闭卷限时）：
- Java(Day50)：写 `? extends/? super` 合法读写并解释 PECS；
- Spring(Day51)：self-invocation 为何让 @Transactional 失效；
- MySQL(Day52)：RC 与 RR 两次读差异 + 行锁；
- Redis/MQ(Day53)：cache-aside 写顺序与 Outbox；
- 安全(Day54)：JWT 校验顺序与 IDOR 修复位置；
- Linux/部署(Day55)：请求超时分层排查命令；
- JVM/性能(Day56)：CPU 高用 jstack/JFR 的证据链；
- 系统设计(Day57)：keyset 分页与稳定排序；
- 微服务(Day58)：两层重试放大倍数；
- 分布式(Day59)：Redis 锁为何还要唯一键/fencing；
- ES(Day60)：text/keyword 区别与 search_after。
答不出的当场标注“不会”，回填对应 Day，不用“看过”冒充掌握。

---

### Q3. 写一页简历项目描述，只用源码/测试/日志能证明的事实。
示例（每条都能指向证据）：
- 基于 Spring Boot 3.4/Java 21 实现题库管理与在线练习模块化单体，覆盖建库、追加式版本发布、练习提交与错题归集闭环；
- 用“Redis 锁（可选）+ FOR UPDATE 行锁 + 幂等键 + 结果快照 + 唯一键兜底”实现提交幂等，并发同键重放结果一致；测试共 27 类 119 个 @Test（默认构建 90 通过 / 29 个真实环境门控跳过 / 0 失败），含 ArchUnit 5 条架构规则、事务传播与失效五路径、jvm 包 4 类 17 法；
- 为发布列表/学生练习/错题查询建立联合索引（V2 迁移），错题只读查询用 MyBatis 动态 SQL + 二级缓存（WrongQuestionQueryMapper 8 用例），真 MySQL EXPLAIN 证据测试门控 MYSQL_EVIDENCE；
- 实现本地/Redis 可切换缓存与限流（条件装配）、Redis 分布式锁（fencing token）、Kafka Outbox（qb-events + consumed_event 幂等 + .DLT）、读写分离 profile、@Async 双池与导入任务退避重试、micrometer Prometheus 指标与 RequestId 全链路日志；cloud/ 另有独立 Spring Cloud 六模块演示（Eureka/Gateway/Feign/Resilience4j/Outbox），与单体不共享代码；deploy/k8s 提供 Deployment+Service+HPA+Secret 演示清单。
**不写**：未接入的 ES、Redis 集群/Redlock、Kafka 精确一次、ShardingSphere 生产化（仅 test scope 证据）、没有实测的 QPS/延迟数字；任何条件装配能力都要同时说明“默认是否开启、证据测试由哪个环境变量门控、是否真跑过”，区分“已实现/已测试/仅设计/未掌握”。

---

## External

### E1. 按目标 JD 模拟面试，按八类记录缺口。
八类：Java、Spring、MySQL、Redis、MQ、网络/Linux、算法、项目。每类记录“被问住的具体问题 + 期望答案要点 + 对应 Day”，形成缺口清单，而不是笼统评价“基础薄弱”。

### E2. 缺口回填 + 1/3/7/14 天复习。
把每个缺口挂到对应 Day：当天弄懂并手写一遍 → 第 1 天默写 → 第 3 天做变式 → 第 7 天混合识别 → 第 14 天限时复现。只有能连续扛住追问、亲手改代码并跑测试才算掌握，“看过文档”不计入。

### E3. 是否真正接入中间件：先写预测、验收标准、回滚方案。
若面试反馈指向某中间件（如 Redis），接入前先写三件事：① **预测**：接入后哪个指标/行为应如何变化（如已发布列表读延迟下降、DB QPS 下降多少）；② **验收标准**：可度量的通过条件（缓存命中率、一致性测试、故障切换行为、新增测试通过）；③ **回滚方案**：特性开关/配置一键退回 DB 直读，数据无破坏。接入并验证后才能写进简历，否则仍是“设计理解”。补强顺序：先 Java/Spring/MySQL/算法打牢，再按 JD 选 1-2 个中间件深入，避免同时堆多个未掌握组件。
