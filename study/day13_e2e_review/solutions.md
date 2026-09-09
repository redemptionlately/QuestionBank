# Day13 E2E Review · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实测试 `M0FlowIntegrationTest`、`InfrastructureUnitTest`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`M0FlowIntegrationTest` 39-90 行完整发布/提交链路、92-149 行角色/题型/错误断言、153-219 行回滚/并发/索引/异步测试。

---

### Q2. 独立写出管理员发布到学生提交的 HTTP 顺序和关键字段。

端到端依赖链（每步都带 `Authorization: Bearer <token>`）：
1. `POST /api/auth/login`（admin/admin123）→ 取 `token`；
2. `POST /api/admin/banks` `{"name":...}` → 返回 `id`（题库 id）；
3. `POST /api/admin/banks/{bankId}/versions`（题面数组）→ 返回 paper `id`、`status=DRAFT`、`versionNo`；
4. `POST /api/admin/versions/{paperId}/publish` → `status=PUBLISHED`、`publishedAt`；
5. 学生登录 → `POST /api/practices` `{"paperVersionId":...}` → 返回会话 `id`、`status=IN_PROGRESS`；
6. `PUT /api/practices/{sessionId}/answers/{questionId}` `{"answer":["A"]}` 逐题保存；
7. `POST /api/practices/{sessionId}/submit`，带头 `Idempotency-Key` → 返回 `totalScore/maxScore/answers[]`；
8. `GET /api/wrong-questions` → 错题数组（`questionId/wrongCount/lastWrongAt`）。

---

### Q3. 运行完整 `mvn test`，指出端到端测试覆盖的数据库结果。
本项目 9 个测试方法（1 单元 + 8 集成），集成测试用 H2 内存库 + Flyway 真实建表，断言的**数据库事实**包括：
- 草稿事务回滚：非法第二题导致整卷不落库（`papers.count()/questions.count()` 前后相等）；
- 提交后 `submission_item` 每题一行、`practice_session.status=SUBMITTED` 且结果 JSON 落库；
- 错题表写入且 `wrongCount` 正确；
- 三个查询索引真实存在（查 `INFORMATION_SCHEMA.INDEXES`）；
- 同 key 并发提交只产生一份稳定结果（行锁串行化）。

---

### Q4. 能定位一次越权、一次状态冲突、一次校验失败和一次重复提交。
- **越权（403）**：student 调 `/api/admin/banks`（`rolesAndValidationAreEnforced`）；
- **状态冲突（409）**：已 SUBMITTED 的会话再 PUT 答案（`gradesAllQuestionTypesAndLocksSubmittedPractice` 末尾断言 409）；
- **校验失败（400）**：submit 不带 body/坏 JSON → `REQUEST_INVALID`（`malformedSubmitRequestIsBadRequestJson`）；
- **重复提交（幂等）**：同 `Idempotency-Key` 两次 submit，响应字符串完全相等（`adminPublishesAndStudentSubmitsIdempotently` 82-85 行）。

---

### Q5. 说明 M0 已验证的工程边界与尚未验证的学习边界。
**已验证（有测试/源码证据）**：三种题型确定性判分、提交不可变、坏请求/越权、草稿事务回滚、查询索引、同 key 并发幂等、异步任务状态流转。
**未验证/明确不做（不能吹成已实现）**：真实 PDF 抽取、Redis 分布式缓存/限流、MinIO 对象存储、消息队列、租约恢复、Prometheus 注册表、分布式链路追踪、Agent/Harness；本地缓存/限流/指标都是**单进程**实现，进程重启即丢失。

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
内存 token 重启失效（需重新登录），但已提交数据在 MySQL（或测试 H2 除外）中持久化：重新登录后凭 sessionId 可 `GET /api/practices/{id}` 读到 SUBMITTED 状态、总分与结果 JSON。这区分了“会话状态（内存、易失）”与“业务事实（数据库、持久）”。

### E3. 记录一个测试缺口并设计最小补测，不扩大组件范围。
示例缺口：`PUT answers` 对“答案包含不属于本题的选项”目前依赖 `validateChoices`，但没有专门测试。最小补测：在现有 `M0FlowIntegrationTest` 增加一个 `@Test`，走登录→发布→建会话的既有夹具，对合法题目提交 `{"answer":["Z"]}`，断言 400 `INVALID_INPUT` 且 `submission_item` 无新增行。只补一个用例，不引入新组件、不改架构。
