# Day21 M0 Architecture · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实包结构（app 模块 9 个业务/基础包共 68 个主类：auth 10、bank 11、cache 3、common 12、event 8、importjob 7、ops 2、practice 11、redis 3，加根启动类）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[BankController.java:15-81](../../app/src/main/java/com/allen/questionbank/bank/BankController.java)（@RequestMapping("/api") :16，5 个端点 :26-58，6 个请求/响应 record :74-80）；[PracticeController.java:15-76](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java)（类级 `@PreAuthorize("hasRole('STUDENT')")` :20，5 个端点 :37-70，可选 RedisGuardedPracticeSubmitter 注入 :29-35，record :72-75）；[BankService.java:19-152](../../app/src/main/java/com/allen/questionbank/bank/BankService.java)、[PracticeService.java:18-214](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 的模块职责与 7/5 处事务边界。

---

### Q2. 不看源码画出登录到提交的时序。

```
学生端                auth                bank                practice             MySQL/Redis/Kafka
 │ POST /api/auth/login │                    │                    │
 │─────────────────────▶│ AuthService.login  │                    │
 │                      │ BCrypt.matches+issue token（readOnly 事务）│                    │
 │◀──── token ──────────│                    │                    │
 │ (管理员) POST admin/versions/{id}/publish  │                    │
 │──────────────────────────────────────────▶│ BankService.publish 一个事务：
 │                                            │ 状态机 DRAFT→PUBLISHED + 同事务写 outbox_event
 │                                            │ 注册 afterCommit：提交后才 evict 已发布缓存
 │◀──────────────── PUBLISHED paperId ───────│                    │
 │ POST /api/practices {paperVersionId}      │                    │
 │───────────────────────────────────────────┼───────────────────▶│ requirePublished(bank Service)
 │◀──────────────── sessionId(IN_PROGRESS) ──┴────────────────────│
 │ PUT /practices/{id}/answers/{qid} (多次)  │                    │ saveAnswer/UPSERT  │
 │───────────────────────────────────────────────────────────────▶│
 │ POST /practices/{id}/submit + Idempotency-Key                  │ 可选 Redis 锁守卫 → submit
 │───────────────────────────────────────────────────────────────▶│ FOR UPDATE 行锁/判分
 │                                                                │ Item+Session+WrongQuestion 同事务
 │◀──────── SubmitResult(total/max/answers) ──────────────────────│
 │ GET /api/wrong-questions（筛选/分页/聚合走 MyBatis mapper XML） │
```

---

### Q3. 列出每步输入、输出、事务、失败码。

| 步骤（端点） | 输入 | 输出 | 事务 | 主要失败码（真实 code） |
|---|---|---|---|---|
| login POST /api/auth/login | username/password | token+userId/role | `@Transactional(readOnly=true)`（AuthService:21） | 401 AUTH_INVALID（用户不存在/禁用/密码错同文案） |
| createBank POST /api/admin/banks | name/description | BankResponse | 写事务（BankService:38） | 400 INVALID_INPUT、403 FORBIDDEN |
| createDraft POST /api/admin/banks/{id}/versions | title+questions | PaperResponse(DRAFT) | 整卷一个事务（:44） | 400 INVALID_INPUT、403 FORBIDDEN、404 NOT_FOUND |
| publish POST /api/admin/versions/{id}/publish | paperId | PUBLISHED+publishedAt，同事务写 Outbox | 写事务（:64） | 404 NOT_FOUND、403 FORBIDDEN、409 STATE_CONFLICT、400 INVALID_INPUT（空卷） |
| create practice POST /api/practices | paperVersionId | session(IN_PROGRESS) | 写事务（PracticeService:37） | 409 STATE_CONFLICT（未发布）、404 |
| saveAnswer PUT .../answers/{qid} | questionId+answer JSON | AnswerView | 写事务（:43） | 404 NOT_FOUND、409 STATE_CONFLICT（已提交）、400、403 FORBIDDEN |
| submit POST .../submit | Idempotency-Key 请求头 | SubmitResult | Item+Session+WrongQuestion 同事务（:56） | 400 IDEMPOTENCY_KEY_REQUIRED、409（异键重复提交）；Redis 锁开启时抢锁失败 409 SUBMIT_IN_PROGRESS |
| wrongBook GET /api/wrong-questions | wrongCountMin/wrongSince/page/size | summary+分页行 | readOnly（:105，MyBatis） | 400 INVALID_INPUT（page<0、size 越界 1-200）、403 |

---

### Q4. 写出 Auth、Bank、Practice 三模块边界与 Controller→Repository 调用方向。
- **auth（10 类）**：登录、令牌签发/解析（TokenService 内存表）、当前用户（`CurrentUser.require()`/`AuthPrincipal`）、SecurityConfig 过滤链，拥有 user_account 数据；
- **bank（11 类）**：题库 QuestionBank、版本 PaperVersion、题目 QuestionVersion 的创建/发布/查询，是“题面事实”的所有者；发布时同事务写 Outbox、提交后淘汰 PublishedPaperCache；
- **practice（11 类）**：练习会话、答案、判分、幂等、错题，是“作答事实”的所有者；复杂错题只读查询走 MyBatis（WrongQuestionQueryMapper + XML），与 JPA 双栈；
- **支撑包**：common（12，异常/错误体/本地缓存/本地限流/RequestId/异步池/读写分离装配等横切设施）、cache（3，PublishedPaperCache 接口 + local/redis 双实现）、event（8，Kafka Outbox 八组件，kafka.enabled 条件装配）、redis（3，分布式锁 + Lua 令牌桶限流）、ops（2，延迟切面 + DB 健康指标）、importjob（7，异步导入与失败重试）。
调用方向严格为 `Controller → Service → Repository → MySQL`；Controller 不碰 Repository、业务域之间不碰对方 Repository——这两条由 ArchitectureTest 的 controllersDoNotTouchRepositoriesDirectly（:52）与 crossDomainRepositoryAccessIsForbidden（:35）做成可执行测试；practice 需要题面时调 bank 的 Service（`requirePublished`/`questions`），不反向依赖。

---

### Q5. 画出两个请求经过三模块的依赖箭头，标出 MySQL 事实来源。
```text
请求A（管理员发布）：
CurrentUser(auth) → BankController → BankService ─→ Paper/Question/Bank Repository → MySQL(事实)
                                    ├→ OutboxPublisher（同事务 INSERT outbox_event；kafka.enabled=false 时 ObjectProvider 缺省=no-op）
                                    └→ PublishedPaperCache（cache 包，afterCommit 才 evict；local 默认/redis 可切）

请求B（学生提交）：
CurrentUser(auth) → PracticeController ─→（可选）RedisGuardedPracticeSubmitter(redis 包，锁在事务外)
                      → PracticeService ─→ BankService.requirePublished/questions（跨域只读，走 Service）
                      ├→ Practice/Submission/Wrong Repository（JPA 写入）→ MySQL(事实)
                      └→ WrongQuestionQueryMapper（MyBatis 只读）→ MySQL
```
**MySQL 是唯一正式事实来源**；HTTP 响应是 DTO record 投影（不暴露实体内部字段）；`submission_result_json` 是提交结果快照，用于幂等重放返回同一份结果；Redis 缓存/锁/限流、内存指标都是派生或协调数据，Redis 不可用时缓存降级回库、正确性最终由 DB 行锁与唯一约束兜底。

---

## External

### E1. 设计审计事件。
在关键状态转换（发布、提交、登录失败、越权拒绝）产出审计事件：谁（userId）、对什么资源（类型+id）、动作、前后状态、requestId（MDC key 固定 `requestId`）、时间、结果。两种实现：①同事务写审计表（强一致但增加事务负担）；②事务提交后发事件（`@TransactionalEventListener(AFTER_COMMIT)`，只有真正提交成功才记录）。本项目实际采用的是 **Transactional Outbox**：发布路径在同一事务内 INSERT outbox_event（V4 建表，uk_outbox_dedup 三元组去重），提交后由 OutboxRelay 轮询投递 Kafka（topic `qb-events`、group `question-bank`、失败进 `.DLT`），消费端用 consumed_event 表幂等——“库里改了但消息没发出去”在结构上不可能发生。

### E2. 对照 verified / learned（重构后四态区分）。
- **①已实现且默认测试验证**：90/119 个 @Test 默认运行通过（H2/纯单元/Spring 上下文），如四题型判分、幂等重放、回滚五路径、事务传播、索引存在、角色拦截、本地缓存 singleflight、固定窗口限流、ImportJob 重试退避、MyBatis 动态 SQL/二级缓存、ArchUnit 5 规则；
- **②已实现但环境门控**：29 个 @Test 由 `*_EVIDENCE` 环境变量门控、默认 Assumption 跳过——真 Redis（11）、真 Kafka（2）、真 MySQL（7）、读写分离（3）、ShardingSphere（6）；代码真实存在，证据要自备环境，不能说“默认构建已验证”；
- **③仅设计/未实现**：必须逐处核对，不把设计当成果（如 Redlock/Redis 集群、ES、配置中心均未接入）；
- **④cloud/ 独立演示**：cloud 下 6 个微服务是独立 Spring Cloud 工程（独立聚合 POM、独立包名 com.allen.cloud、独立三库），与 app 不共享代码，不计入 app 的 verified。
简历和复盘只陈述 verified 事实，门控项注明“需环境复现”，learned/未接入内容必须显式标注，不把理论当成果；也严禁引用未实际跑出的性能数字。
