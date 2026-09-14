# Day28 M0 Interview Review · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实核心类与测试证据（app 模块；全量 27 测试类 119 个 @Test，默认 90 通过 / 29 环境门控跳过 / 0 失败）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[BankService.java:44-62](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 草稿、`:64-81` 发布（同事务 Outbox :75-78、afterCommit 缓存淘汰 :87-98）；[PracticeService.java:56-89](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 提交/幂等；[PracticeSessionRepository.java:11-17](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 悲观锁查询；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) :49-86（practice_session/submission_item/wrong_question 三表与唯一键）、V3/V4 另建 import_job 与 outbox_event/consumed_event；`M0FlowIntegrationTest` 回滚 :154-177、并发 :179-213、索引 :215-220。

---

### Q2. 回答数据流和技术取舍。
后端项目陈述按固定事实结构展开：**业务目标 → 核心实体 → 请求数据流 → 事务边界 → 并发控制 → 持久化结果 → 失败响应**。
- 业务目标：管理员建题库/发布不可变试卷，学生练习、确定性判分、错题归集；
- 核心实体：QuestionBank→PaperVersion→QuestionVersion（题面聚合），PracticeSession→SubmissionItem/WrongQuestion（作答聚合）；
- 数据流：Controller（协议/DTO record）→ Service（授权+状态机+事务）→ Repository（JPA 写/等值读，MyBatis 只做错题复杂只读）→ MySQL；发布事件同事务写 Outbox、提交后由 Relay 投递 Kafka；
- 取舍：JPA（聚合 CRUD 清晰）+ MyBatis（动态 SQL/聚合可读、EXPLAIN 可见）双栈、标量外键 ID（避免懒加载/级联失控）、追加式版本（历史快照可审计）、服务端判分（客户端不可信）、条件装配（cache/lock/rate-limit/kafka/import.retry 开关，默认能力与增强能力可切）。

---

### Q3. 运行测试并复述证据。
M0FlowIntegrationTest 现共 **8** 个 @Test：adminPublishesAndStudentSubmitsIdempotently（:39 主链路幂等提交）、rolesAndValidationAreEnforced（:93 角色与校验）、gradesAllQuestionTypesAndLocksSubmittedPractice（:104 三题型判分+提交锁定）、malformedSubmitRequestIsBadRequestJson（:145 坏请求 400 JSON）、invalidQuestionRollsBackTheWholeDraftTransaction（:154 草稿整体回滚）、concurrentSubmitWithSameKeyReturnsOneStableResult（:179 同 key 并发唯一结果）、queryIndexesExistForPublishedAndStudentReadPaths（:215 索引存在）、importJobIsAcceptedAndCompletesAsynchronously（:222 导入异步流转）；“缓存只加载一次”由 InfrastructureUnitTest.cacheLoadsOnceUntilEvicted 与 common/ExpiringCacheSingleflightTest 6 法承担。复述时区分“测试证明了什么”（当前输入/环境下断言通过）与“没证明什么”（真实规模性能、跨实例；门控证据需自备 Redis/Kafka/MySQL 环境）。

---

### Q4. 针对一个方案写出观测事实、推断、未知、替代方案和残余限制。
以“提交用悲观行锁”为例：
- **观测事实**：同 key 并发测试只产生一份结果（:209 两响应体相等）；`findByIdForUpdate` 生成 FOR UPDATE；真 MySQL 锁等待由门控测试 forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout 固化；
- **推断**：行锁串行化了同一会话的状态检查与写入（由 InnoDB 锁机制支撑）；
- **未知**：高并发混合不同会话时的锁等待分布、真实吞吐（压测数字只来自 tools/loadgen 特定环境，不能外推）；
- **替代方案**：乐观锁 @Version（PracticeSession:19 已保留）、唯一键 + 插入冲突重试、通用幂等表（对照 consumed_event）、**Redis 分布式锁（本项目已实现：RedisLockService SET NX PX + Lua 比对 token 释放 + fencing token，经 RedisGuardedPracticeSubmitter 接到提交路径，app.lock.backend=redis 开启）**；
- **残余限制**：行锁只护同一行，不解决 key TTL/处理中恢复/跨业务幂等；Redis 锁减少冲突但不替代 DB 锁与唯一键（绕过 Redis 锁时 DB 仍保正确，RedisGuardedSubmitIntegrationTest:84）。

---

### Q5. 按源码索引说明各组件负责的事实。
| 组件 | 负责的事实 |
|---|---|
| BankService | 题库创建、草稿版本号递增与题目校验（:44-62/:129-138）、发布状态机与幂等、同事务 Outbox（:75-78）、afterCommit 缓存失效（:87-98） |
| PracticeService | 会话创建状态校验、答案 UPSERT、确定性判分、幂等提交（:56-89）、错题聚合写入与 MyBatis 只读查询（:105-115） |
| PracticeSessionRepository | `findByIdForUpdate` 悲观写锁（:14-16）、学生维度派生查询（:12） |
| cache/redis/event/ops 包 | PublishedPaperCache 双实现与降级、Redis 锁/令牌桶、Outbox 八组件、延迟切面与 DB 健康指标（均条件装配） |
| V1~V4 SQL | 11 张表/外键/联合唯一键（永久结构事实）、V2 查询索引、V3/V4 过程索引 |
| 27 个测试类 | 默认 90 法行为证据 + 29 法环境门控证据（Redis/Kafka/真 MySQL/读写分离/分库分表） |

---

### Q6. 口述同 key 提交从请求头到数据库重放的完整因果链。
`请求带 Idempotency-Key → PracticeController.submit（:46-53）@RequestHeader 取出 → CurrentUser.require() 得到学生 → 若 app.lock.backend=redis 则先经 RedisGuardedPracticeSubmitter（锁 practice-submit:{sessionId}、TTL 10s，抢锁失败 409 SUBMIT_IN_PROGRESS，锁在事务外）→ PracticeService.submit 校验 key（缺失/空白/超长 400，:58-60）→ findByIdForUpdate 对 session 行加 X 锁（:61）→ 校验 studentId（:62）→ 读状态：
- SUBMITTED 且 key 相同 → readResult 反序列化 submission_result_json 返回（:63-65，不判分、不写错题）；
- SUBMITTED 且 key 不同 → 409 STATE_CONFLICT（:64）；
- IN_PROGRESS → 遍历 QuestionVersion 判分、写 SubmissionItem/WrongQuestion（:70-84）、session.submit 落总分与结果 JSON（:86-87）→ 事务提交释放行锁（Redis 锁在 finally 释放）→ 返回 SubmitResult。`

---

### Q7. 比较悲观锁、乐观锁、唯一键在 M0 三个并发冲突中的适用位置。

| 机制 | 原理 | 本项目适用冲突 | 代价 |
|---|---|---|---|
| 悲观锁 FOR UPDATE | 先锁行再读写，事务结束释放 | **提交幂等**：同一会话并发提交必须强串行（Repository:14-16） | 持锁期间阻塞，事务要短 |
| 乐观锁 @Version | 提交时比对版本，冲突报错/重试 | PracticeSession（:19）与 ImportJob（:19）保留 entityVersion，适合冲突少、读多写少 | 冲突频繁时重试成本高 |
| 数据库唯一键 | 插入时靠唯一索引兜底拒绝重复 | uk_paper_version_no、uk_question_version_no、uk_submission_question、uk_wrong_question、uk_outbox_dedup、uk_consumed_event_key 是**最终防线** | 只能拒绝，不能合并/排序 |
| Redis 分布式锁（补充层） | SET NX PX + Lua 持 token 释放 + 单调 fencing token | 跨实例减压、把同 session 并发挡在事务之前（可开关） | 依赖 Redis 可用性，不能替代 DB 兜底 |
前三者互补：悲观锁管“同一行的检查-写入临界区”，乐观锁管“低冲突更新不互相覆盖”，唯一键在任何并发/绕应用场景下兜底；Redis 锁是可选外层，四层缺一的语义必须能讲清（项目里确实有“绕过 Redis 锁、DB 仍正确”的门控证据）。

---

## External

### E1. 录音复盘。
按 Q2 结构口述 2-3 分钟并录音回听：检查是否把“计划/推断”说成“已实现/已验证”（特别区分：默认测试已验证 vs 环境门控证据 vs 仅设计 vs cloud 独立演示四态）、是否能在被追问时落到具体类与行号、技术取舍是否同时给出替代方案与代价。卡顿点就是要补的知识缺口。

### E2. 简历句子绑定源码/日志。
每条简历表述都要能指向证据，例如：
- “实现提交接口幂等，重复提交返回一致结果” → PracticeService.submit :56-89 + M0Flow 并发测试 :179-213 + output/e2e_submit.json；
- “通过联合唯一索引和事务保证版本/错题不重复” → V1 唯一键 + V2 索引 + output/mysql_explain_20260818.log；
- “实现 Redis 缓存/锁/限流与 Kafka Outbox” → cache/redis/event 包 + 对应门控集成测试（必须注明证据需 REDIS_EVIDENCE/KAFKA_EVIDENCE 环境复现）。
写不出证据的句子（如“支撑万级 QPS”、Redis 集群/Redlock、ES）在没有证据前不写——docs/interview/resume_one_page.md 有“明确不写”清单。遵循 STAR：情境(S)、任务(T)、行动(A，绑定源码)、结果(R，绑定测试/日志数据，且数字只引用实测环境)。
