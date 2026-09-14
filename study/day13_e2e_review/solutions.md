# Day13 E2E Review · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实测试 `M0FlowIntegrationTest`、`InfrastructureUnitTest`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（测试在 `app/src/test/java/com/allen/questionbank/`）：`M0FlowIntegrationTest` :39-90 完整发布/提交链路、:93 角色越权、:114 坏 JSON、:133 三题型判分、:154 草稿回滚、:179 同 key 并发、:203 索引存在性、:222 异步导入任务；另有 OutboxTest 9 个、WrongQuestionQueryMapperTest 8 个、jvm 包 17 个等，全量 27 类 119 个 @Test。

---

### Q2. 独立写出管理员发布到学生提交的 HTTP 顺序和关键字段。

端到端依赖链（每步都带 `Authorization: Bearer <token>`）：
1. `POST /api/auth/login`（演示账号由 DevDataInitializer 播种）→ 取 `token`；
2. `POST /api/admin/banks` `{"name":...}` → 返回 `id`（题库 id）；
3. `POST /api/admin/banks/{bankId}/versions`（题面数组）→ 返回 paper `id`、`status=DRAFT`、`versionNo`；
4. `POST /api/admin/versions/{paperId}/publish` → `status=PUBLISHED`、`publishedAt`，同时同事务写 outbox_event（PAPER_PUBLISHED，Kafka 关闭时 no-op）、提交后淘汰已发布缓存；
5. 学生登录 → `POST /api/practices` `{"paperVersionId":...}` → 返回会话 `id`、`status=IN_PROGRESS`；
6. `PUT /api/practices/{sessionId}/answers/{questionId}` `{"answer":["A"]}` 逐题保存；
7. `POST /api/practices/{sessionId}/submit`，带头 `Idempotency-Key` → 返回 `totalScore/maxScore/answers[]`；
8. `GET /api/wrong-questions?page=0&size=50`（可选 wrongCountMin/wrongSince）→ `WrongBook{summary{totalQuestions,totalWrongCount,maxWrongCount}, items[{questionVersionId,wrongCount,lastWrongAt}], page, size}`（MyBatis 动态 SQL 分页聚合）。

---

### Q3. 运行完整 `mvn test`，指出端到端测试覆盖的数据库结果。
全量为 **27 个测试类、119 个 @Test**；默认 `mvn test`（不设环境变量）实测 **90 通过 / 0 失败 / 29 跳过**——29 个跳过是 `@EnabledIfEnvironmentVariable` 门禁（REDIS_EVIDENCE 11、MYSQL_EVIDENCE 7、MYSQL_SHARDING_EVIDENCE 6、KAFKA_EVIDENCE 2、READ_REPLICA_EVIDENCE 3），需要真实中间件时打开对应开关才运行，不是失败。默认集成测试用 H2 内存库 + Flyway 真实建表，断言的**数据库事实**包括：
- 草稿事务回滚：非法第二题导致整卷不落库（`papers.count()/questions.count()` 前后相等，M0FlowIntegrationTest:154）；
- 提交后 `submission_item` 每题一行、`practice_session.status=SUBMITTED` 且结果 JSON 落库；
- 错题表写入且 `wrongCount` 正确；MyBatis 错题动态 SQL 的筛选/分页/聚合/二级缓存由 WrongQuestionQueryMapperTest 8 个用例覆盖；
- 三个查询索引真实存在（查 `INFORMATION_SCHEMA.INDEXES`，:203）；
- 同 key 并发提交只产生一份稳定结果（FOR UPDATE 行锁串行化，:179）；
- Outbox 同事务落箱/去重/消费幂等等 9 个事实由 OutboxTest 覆盖；真实 MySQL/Redis/Kafka/读写分离/分库分表证据在对应开关打开后由 7+11+2+3+6 个门控测试验证。

---

### Q4. 能定位一次越权、一次状态冲突、一次校验失败和一次重复提交。
- **越权（403）**：student 调 `/api/admin/banks`（`rolesAndValidationAreEnforced`，M0FlowIntegrationTest:93）；
- **状态冲突（409）**：已 SUBMITTED 的会话再 PUT 答案（`gradesAllQuestionTypesAndLocksSubmittedPractice`（:133）末尾断言 409；换 key 重提也是 409）；
- **校验失败（400）**：坏 JSON → `REQUEST_INVALID`（`malformedSubmitRequestIsBadRequestJson`，:114）；submit 缺 `Idempotency-Key` → `IDEMPOTENCY_KEY_REQUIRED`；
- **重复提交（幂等）**：同 `Idempotency-Key` 两次 submit，响应字符串完全相等（`adminPublishesAndStudentSubmitsIdempotently`，:70-90 区间内断言）。

---

### Q5. 说明已验证的工程边界与尚未验证的学习边界（必须区分四态，不能一刀切）。
**① app 已真实实现（有类:行证据）**：三题型确定性判分、提交不可变、坏请求/越权、草稿事务回滚、查询索引、同 key 并发幂等、异步导入任务状态流转；以及重构后新落地的——PublishedPaperCache 三实现缓存（local/redis/none，发布后 afterCommit 淘汰）、RedisLockService 分布式锁、Redis 令牌桶限流（RedisRateLimitFilter+RedisTokenBucketRateLimiter，与内存固定窗口并存）、Kafka Outbox（event 包 8 类，topic=qb-events、group=question-bank、DLT=.DLT，Kafka 关闭时整链 no-op）、读写分离（ReadReplicaDataSourceConfig+ReplicationRoutingDataSource）、ServiceLatencyAspect 延迟切面、DatabaseLatencyHealthIndicator 健康指标、RequestIdFilter（MDC key=requestId）、ImportJobRetrySweeper 退避重试、practice 提交 Redis 防护、MyBatis 错题查询。
**② 已被测试验证（119 个 @Test）**：默认 90 个（H2 可跑，含 ArchUnit 5 规则、Outbox 9、MyBatis 8、jvm 17 等），29 个需环境变量门控——REDIS_EVIDENCE（RedisCacheIntegrationTest 3、RedisLockAndRateLimitIntegrationTest 6、RedisGuardedSubmitIntegrationTest 2）、MYSQL_EVIDENCE（MysqlRealDbIntegrationTest 7）、MYSQL_SHARDING_EVIDENCE（ShardingEvidenceTest 6，ShardingSphere 仅 test scope）、KAFKA_EVIDENCE（OutboxKafkaIntegrationTest 2）、READ_REPLICA_EVIDENCE（ReadReplicaIntegrationTest 3）。
**③ 仍仅设计/未实现（不可吹成已做）**：真实 PDF 抽取、MinIO 对象存储、租约恢复、分布式链路追踪（仅有 requestId/MDC 与 ServiceLatencyAspect 计时，不是全链路 trace 系统）、Agent/Harness；内存 TokenService 重启失效、local 缓存/固定窗口限流仍是单进程实现。
**④ cloud/ 独立演示（不算 app 内置）**：`cloud/` 下 Eureka+网关+auth+bank+practice+cloud-common 六个服务是独立可运行的微服务演示，与 app 模块化单体不共享代码，不能说成 app 主链路已内置微服务。

---

### Q6. 分别用 MockMvc 和真实 HTTP 客户端验证认证、状态转换、数据库结果和错误响应。
- **MockMvc（本项目采用）**：`@SpringBootTest + @AutoConfigureMockMvc`，进程内走完整 Spring MVC（过滤器→Controller→Service→Repository→H2），不占真实端口，速度快，可直接 `@Autowired` Repository 校验数据库；
```java
mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
       .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
   .andExpect(status().isOk());
```
- **真实 HTTP（RANDOM_PORT + TestRestTemplate/WebClient）**：起真实监听端口，经过完整网络栈与序列化，更接近生产，用于验证过滤器链、Content-Type 协商等协议细节；代价是更慢。
- 两者都要断言四层：HTTP 状态、业务 code、返回 JSON 字段、数据库持久化状态。

---

## External

### E1. 在每个步骤插入网络重试，确认哪些请求安全重试、哪些需要幂等键。
- 天然安全重试：GET（只读、无副作用）、登录（重复签发 token 无破坏性）；
- 幂等化后可重试：publish（重复发布返回同一版本）、submit（**必须携带相同 Idempotency-Key** 才能安全重试）；
- 不可盲目重试：无幂等键的 POST 创建类（重复建题库/草稿会产生多条）。结论：写操作重试必须由业务幂等键或唯一约束保护。

### E2. 服务重启后重新登录并查询历史提交，确认正式数据仍在。
内存 token 重启失效（需重新登录，TokenService 是 ConcurrentHashMap），但已提交数据在 MySQL（或测试 H2 除外）中持久化：重新登录后凭 sessionId 可 `GET /api/practices/{id}` 读到 SUBMITTED 状态、总分与结果 JSON；Outbox 未发事件也会在重启后由 OutboxRelayScheduler 继续投递（at-least-once + 消费去重）。这区分了“会话状态（内存、易失）”与“业务事实（数据库、持久）”。若 `app.cache.backend=redis`，已发布列表缓存还能跨进程共享；local 后端则随重启清空、回源重建。

### E3. 记录一个测试缺口并设计最小补测，不扩大组件范围。
示例缺口：`PUT answers` 对“答案包含不属于本题的选项”目前依赖 `validateChoices`，但没有专门测试。最小补测：在现有 `M0FlowIntegrationTest` 增加一个 `@Test`，走登录→发布→建会话的既有夹具，对合法题目提交 `{"answer":["Z"]}`，断言 400 `INVALID_INPUT` 且 `submission_item` 无新增行。只补一个用例，不引入新组件、不改架构。
