# Day48 Resume Project Story · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实主链路（common 过滤器链、bank/practice 两域、event Outbox、cache/redis 条件装配、ops 观测），以及 docs/interview/resume_one_page.md 的证据化简历口径。测试总盘子：27 个测试类 119 个 @Test，默认构建 **90 通过 / 29 环境门禁跳过 / 0 失败**（2026-09-09 实测，见 _baseline_buildtest.md）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（行号取自真实代码）：
- HTTP 主链路：[common/RequestIdFilter.java:22-41](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java)（requestId/MDC）→ [common/RateLimitFilter.java:22-65](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java)（本地固定窗口，默认；切 `app.rate-limit.backend=redis` 时由 [redis/RedisRateLimitFilter.java:25-55](../../app/src/main/java/com/allen/questionbank/redis/RedisRateLimitFilter.java) + Lua 令牌桶替代）→ [auth/ApiTokenFilter.java:16-38](../../app/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java)（Bearer→SecurityContext）→ [bank/BankController.java:15-80](../../app/src/main/java/com/allen/questionbank/bank/BankController.java)、[practice/PracticeController.java:15-72](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java)；
- 事务/事实边界：[bank/BankService.java:19-124](../../app/src/main/java/com/allen/questionbank/bank/BankService.java)（发布写事务 :64、同事务 Outbox :74-77、afterCommit 缓存淘汰 :87-96、缓存读 :107）；[practice/PracticeService.java:18-176](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java)（submit :56-57 幂等+FOR UPDATE 判分，view :91-92，wrongBook :105-113 走 MyBatis）；
- 可选 Redis 提交守卫：[practice/RedisGuardedPracticeSubmitter.java:25-48](../../app/src/main/java/com/allen/questionbank/practice/RedisGuardedPracticeSubmitter.java)（app.lock.backend=redis 时装配，锁名 practice-submit:{sessionId}、TTL 10s、抢锁失败 409）。

---

### Q2. 3/8/15 分钟复述。
- **3 分钟（电梯版）**：一句话定位（面向题库管理与在线练习的模块化 Spring Boot 3.4 / Java 21 后端，9 包 68 个生产类）→ 核心能力（管理员建题库/发布不可变版本并经事务发件箱广播 PAPER_PUBLISHED；学生练习、服务端确定性判分、MyBatis 错题查询）→ 两个技术亮点（①提交幂等三层：Redis 锁→SELECT…FOR UPDATE 行锁→uk_submission_question 唯一键；②cache-aside 发布同事务写 Outbox、afterCommit 才淘汰缓存，Redis 故障自动降级回库）→ 验证方式（119 个 @Test：90 个默认全绿，29 个真 Kafka/MySQL/Redis/读写分离/分库分表证据用例由环境变量门控，0 失败）。
- **8 分钟（主链路版）**：按请求逐层讲：RequestId/限流/Token 过滤器链与 Security 方法级授权 → 建题库/草稿（单事务整体回滚）→ 发布 DRAFT→PUBLISHED（同事务 outbox_event、Relay 1s 轮询同步等 ack 投 qb-events、消费端 consumed_event 幂等、毒丸重试 2 次进 .DLT）→ 练习绑定不可变题目快照 → 提交（可选 Redis 锁串行化、行锁、四题型 canonical 判分、错题 upsert、结果 JSON 幂等重放）→ 统一错误契约与观测（ErrorResponse 带 requestId、Actuator health/metrics/prometheus、AOP 慢调用 200ms 日志、DB 探针 SELECT 1/500ms、本地/Redis 双缓存与本地/Redis 双限流可切换）。
- **15 分钟（深挖版）**：每个难点按"不变量→并发冲突→失败回滚→替代方案→证据→残余限制"展开，并**主动如实划分边界**：已实现且有测试——Redis 缓存/锁/令牌桶、Kafka Outbox+幂等消费+DLT、读写分离 Routing DataSource（H2 测试默认跑）、ImportJob 指数退避重试（30<<n 封顶 600、最多 3 次）、JPA/MyBatis 双栈、自研 monitor-starter、ArchUnit 5 规则、JVM/JUC 17 个证据用例；仅设计未实现——PDF 上传/文本抽取、候选审核流、对象存储、事件 schema 版本化、Resilience4j 熔断（注解式熔断只在独立 cloud/ 演示里）；仅 test scope——ShardingSphere 分库分表 6 证据。另可说明 cloud/ 六服务微服务演示（Eureka/网关/auth/bank/practice + cloud-common）与单体不共享代码，是"会拆"的对照而非主线能力。

---

### Q3. 每个数字指出证据。
凡说数字必须绑定测量条件：请求模型、数据规模、并发度、硬件、JDK/软件版本、是否预热、重复次数、统计分位。
- **测试数字（可直接说，指向 _baseline_buildtest.md 与 surefire-reports）**：27 测试类 / 119 @Test；默认构建 90 通过、29 个 `*_EVIDENCE` 门控跳过、0 失败；门控分布 KAFKA 2 / MYSQL 7 / READ_REPLICA 3 / REDIS 11 / SHARDING 6；
- **压测数字（仅可按 docs/interview/resume_one_page.md 的绑定条件说）**：开环压测器 Loadgen（虚拟线程、warmup 10s 后取样、P50/P95/P99/P999）；固定环境（32C32G Win11、JDK21.0.12、G1 -Xms512m-Xmx1g MaxGCPauseMillis=200、MySQL 9.0.x 同机、Hikari max=10、5000/500 种子数据、压测时限流放宽）下 published@100RPS P50≈2/P95≈5ms、login@20RPS 约 15.6-16.1ms（BCrypt 为 CPU 大头，JFR 占约 84%）、G1 23 次停顿合计约 147ms、Full GC 0；脱离该环境不得引用；
- **不可说**：没有测量就写"支撑 X QPS / 毫秒级"；把门控跳过的 29 个说成"每次构建都跑"；把 cloud 的数字安到 app 头上。缺测量条件的性能数字没有可比性，应删除。

---

### Q4. 从 HTTP 请求写到 Controller、Service、Repository、SQL、事务提交、响应序列化。
以提交为例完整走一遍（真实顺序）：
1. **HTTP**：`POST /api/practices/{sessionId}/submit`，带 `Authorization: Bearer` 与强制的 `Idempotency-Key` 头；
2. **过滤器链**：RequestIdFilter（透传/生成 X-Request-Id、MDC key="requestId"、响应头回写）→ RateLimitFilter/RedisRateLimitFilter（计数、超限 429+Retry-After）→ ApiTokenFilter（substring(7) 取 token、AuthPrincipal 入 SecurityContext）；
3. **Controller**：类级 `@PreAuthorize(STUDENT)`，PracticeController:46-47 绑定路径/请求头；若 app.lock.backend=redis，先经 RedisGuardedPracticeSubmitter（practice-submit:{sessionId} SET NX PX 10s，抢不到 409 SUBMIT_IN_PROGRESS）；
4. **Service（事务开始，PracticeService.submit :56-57）**：校验幂等键 → 悲观锁查询（SELECT…FOR UPDATE）→ 校验归属与状态 → 逐题 canonical 判分（normalize 后比对）→ 写 submission_item / upsert wrong_question → session.submit(key,score,json) 落总分、结果 JSON 与 submission_key；错题读侧查询走 MyBatis WrongQuestionQueryMapper（动态 SQL + 二级缓存 60s），与 JPA 写入双栈；
5. **Repository/SQL**：JPA 生成 SELECT/UPDATE/INSERT，uk_submission_question(session_id,question_version_id) 与 submission_key 做最终兜底；Flyway V1~V4 共 11 张表；
6. **事务提交**：代理 commit、释放行锁；Redis 守卫在 finally 释放 Lua 比对 token 的锁（锁在事务外、提交后释放）；
7. **响应序列化**：响应 record 经 Jackson 序列化，ErrorResponse 路径由 GlobalExceptionHandler 统一带 code/requestId/timestamp，server.error.include-message=never 不泄露内部信息。
每一步都要能说出输入、输出和失败边界（key 缺失 400、越权 403、锁冲突/重复提交 409、锁在身但绕过它时 DB 行锁仍保正确——RedisGuardedSubmitIntegrationTest:84 固化）。

---

## External

### E1. 随机源码索引问答。
随机抽一个类/方法，30 秒内说出：它属于哪层、输入输出、依赖谁、条件装配开关是什么（cache.backend / rate-limit.backend / lock.backend / kafka.enabled / import.retry.enabled / @Profile("read-replica")）、失败返回什么、对应哪条测试。建议覆盖面：cache 三实现、redis 三组件、event 八类、ops 两组件、importjob 七类、practice 十一类；抽不出的位置就是掌握盲区，回到源码补齐。

### E2. 删除无日志/无证据数字。
逐条审查简历（直接对照 docs/interview/resume_one_page.md 的 11 条证据 bullet 与"明确不写"清单：Redis 集群/Redlock、ES、配置中心、Bulkhead、多实例扩容、跨机房、对外 QPS 数字均不写）。最终每条 bullet 都能回答"证据在哪（哪个类/哪个测试/哪份 output 日志）"。严格区分四类表述：**当前实现（事实，68 个生产类可点）、已验证行为（证据，119 测试 + output 日志 + JFR）、尚未实现（缺口：PDF/对象存储/审核流）、未来设计（计划）**，不互相替代；cloud/ 六服务能力必须标注"独立微服务演示，与单体不共享代码"。
