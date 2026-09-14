# Day38 M1 Review & Publish · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实代码 `bank/BankService`、`bank/BankController`、`bank/PaperVersion`、V1 表结构与 `event/OutboxPublisher`。**候选审核层（candidate/review_record）整体未实现，是目标设计**；正式版本侧 `DRAFT→PUBLISHED` 是已实现的唯一发布出口，且发布现在同事务写 Outbox、提交后淘汰缓存。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
真实锚点（行号取自真实代码）：
- [bank/PaperVersion.java:8](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java)：`@Entity paper_version`，**真实状态只有 DRAFT/PUBLISHED**（无 ARCHIVED），V1:21-33 建表、uk_paper_version_no UNIQUE(bank_id,version_no)（V1:30）；
- [bank/BankController.java:26-58](../../app/src/main/java/com/allen/questionbank/bank/BankController.java)：POST /api/admin/banks（:26-27）、POST /api/admin/banks/{bankId}/versions（:33-34）、POST /api/admin/versions/{paperId}/publish（:40-41，均 ADMIN）；读：GET /api/papers/published（:46-47）、GET /api/papers/{paperId}（:50-51）；
- [bank/BankService.java:19-20](../../app/src/main/java/com/allen/questionbank/bank/BankService.java)：写事务 :38/:44/:64，只读事务 :100/:105/:117/:124；发布时**同事务** OutboxPublisher.enqueue("paper", paperId, "PAPER_PUBLISHED", payload)（:74-77），并注册 afterCommit 回调（:79→:87）在**事务提交后** publishedCache.evict（:92，无活跃事务时直接 evict :96）；读路径走 PublishedPaperCache.getOrLoad（:107）。
- 回滚证据：M0FlowIntegrationTest.invalidQuestionRollsBackTheWholeDraftTransaction（:154，坏题整笔草稿回滚）。
候选审核层位于发布**之前**，审核通过后才创建正式草稿/版本——该层当前不存在。

---

### Q2. 画状态图。
**候选（导入解析产物，目标设计，当前未实现）**：
```
CREATED → REVIEWING → ACCEPTED ─→ 创建新 DRAFT paper_version →（既有真实流程）PUBLISHED
                    └→ REJECTED（带 reason，终态，可重新发起）
```
**正式版本（真实状态机）**：`DRAFT → PUBLISHED`；`ARCHIVED`（归档不删除、保留历史快照）为**目标扩展，当前枚举不存在**。题目快照 question_version 不可变（V1:35-47，uk_question_version_no），发布即固化题面。
解析只产生候选，**绝不直接改正式题库**；是否进入正式题库由人工审核决定。

---

### Q3. 测非法转换 409。
对 `REJECTED` 候选再执行"通过"、对已 `ACCEPTED` 重复通过、跳过 REVIEWING 直接 ACCEPTED 等，状态机中不存在的边一律返回 409 `STATE_CONFLICT`；actor 无审核权限返回 403（项目统一异常为 ApiException 携带 HttpStatus+稳定 code，GlobalExceptionHandler :23 处理）；候选不存在返回 404。真实发布侧的同类守卫：只允许 ADMIN 调发布端点（SecurityConfig/Security 注解），非法草稿状态发布应被服务层拒绝——这些非法转换测试属于候选层待补测试。

---

### Q4. 写出候选状态、版本状态、审核记录字段、版本检查与追加审计规则。
- **候选状态（设计）**：CREATED/REVIEWING/ACCEPTED/REJECTED；
- **版本状态（真实+设计）**：真实仅 DRAFT/PUBLISHED；ARCHIVED 为设计扩展；
- **审核记录字段（review_record，只追加，设计——当前 11 张表中无此表）**：`id、candidate_id、reviewer_id、decision(ACCEPT/REJECT)、reason、source_version、created_at`；
- **版本检查（如实区分）**：项目中 `@Version entity_version` 乐观锁真实用在 ImportJob（[ImportJob.java:19](../../app/src/main/java/com/allen/questionbank/importjob/ImportJob.java)）与 PracticeSession（:19，V1 practice_session.entity_version），**PaperVersion 表没有 @Version 列**；候选层要防旧页面覆盖，需新增乐观锁或审核时悲观行锁（项目内悲观锁真实例子是 PracticeService.submit 的 SELECT…FOR UPDATE）；并发建版本的数据库级兜底是 uk_paper_version_no 唯一键；
- **追加审计**：每次审核**新增一条记录**，不覆盖上一条意见；撤回/归档/恢复都通过状态转换或新版本表达，绝不 UPDATE 覆盖已发布题面（question_version 不可变快照即此原则的已实现体现）。

---

### Q5. 写出 `fromState + command + actor -> toState` 转换表。
| fromState | command | actor | toState | 非法情况 |
|---|---|---|---|---|
| CREATED | startReview | 有审核权者 | REVIEWING（设计） | 非审核员→403 |
| REVIEWING | accept | 审核员 | ACCEPTED（设计） | 其他状态→409 |
| REVIEWING | reject(reason) | 审核员 | REJECTED（设计） | reason 必填，否则 400 |
| ACCEPTED | createDraft | 系统/管理员 | 新 DRAFT paper_version（设计衔接真实建版本端点 BankController:27-28） | 重复创建→幂等/409，uk_paper_version_no 兜底 |
| DRAFT | publish | ADMIN | PUBLISHED（**真实**：BankService 写事务 :64，同事务写 Outbox :74-77、afterCommit 淘汰缓存 :87-96） | 空题/坏题整笔回滚（:154 测试），非 DRAFT→409 |
| PUBLISHED | archive | ADMIN | ARCHIVED（设计，当前无此状态/端点） | 已归档→409 |
规则：表中没有的边返回 409；actor 不满足角色/所有权返回 403；转换与审核记录在同一事务落库（参照真实发布：状态翻转与 Outbox 行同一事务提交，缓存失效刻意推迟到 afterCommit）。

---

### Q6. 构造旧页面覆盖新审核决定的并发冲突。
审核员 A、B 同时打开同一候选（都基于 entityVersion=1）：A 先 accept（提交后 version→2），B 随后提交 reject，其 `WHERE id=? AND entity_version=1` 匹配不到 → 抛乐观锁异常 → 返回 409，提示数据已被他人修改，需要刷新后再决定。这样"旧页面的 stale 决定"不会覆盖新决定；若用悲观锁则 B 在 A 提交前阻塞、之后读到最新状态。
项目内可直接对照的真实并发控制：ImportJob/PracticeSession 的 @Version 乐观锁；PracticeService.submit 的 SELECT…FOR UPDATE 悲观锁 + 唯一键兜底（M0FlowIntegrationTest.concurrentSubmitWithSameKeyReturnsOneStableResult :179 固化同键并发只出一份结果）；候选层落地时套用同一组合。

---

## External

### E1. 设计双审核并发。
若规则要求"两人审核通过才录用"，候选上记录 `approvals` 集合或独立审核记录计数：状态转换条件改为"收到两个不同审核员的 ACCEPT 才进入 ACCEPTED"，同一人重复通过只计一次；并发提交靠唯一约束 `(candidate_id, reviewer_id)` 防重复计票（可参照真实唯一键模式 uk_outbox_dedup/uk_consumed_event_key 的三元组精确去重），乐观锁保证计数一致；任一 REJECT 按规则进入 REJECTED 或挂起。

### E2. 审计每次转换。
每次状态转换在**同一事务**追加一条不可变审计记录（谁、对哪个候选/版本、什么命令、from→to、原因、时间、requestId——requestId 由 RequestIdFilter 写入 MDC，可直接取），审计表只 INSERT 不 UPDATE/DELETE；项目内真实的"提交后副作用"范式是 BankService:87-96 的 TransactionSynchronization afterCommit（缓存淘汰在事务真正提交后执行，回滚则不淘汰），审计外发事件可套用同一时机（或 `@TransactionalEventListener(AFTER_COMMIT)`），保证审计与业务事实一致、回滚的动作不产生审计；发布的对外事件已由 Outbox 模式保证同事务落库、Relay 异步投递到 topic `qb-events`。
