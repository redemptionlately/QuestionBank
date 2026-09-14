# MustRemember

- 候选状态（设计态，未实现）`CREATED -> REVIEWING -> ACCEPTED/REJECTED`；正式版本真实状态只有 [PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 的 `DRAFT -> PUBLISHED`，ARCHIVED 是设计扩展；题目快照 question_version 不可变
- 真实发布出口：[BankController.java](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) :34-35 `POST /api/admin/versions/{paperId}/publish`（ADMIN）；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) :64 写事务内翻转状态，:74-77 **同事务**写 Outbox（PAPER_PUBLISHED → topic qb-events），:87-96 afterCommit 才淘汰 PublishedPaperCache（回滚则不淘汰），:107 读路径走缓存
- 并发建版本的 DB 兜底：uk_paper_version_no UNIQUE(bank_id,version_no)（V1:30）；坏题整笔草稿回滚见 M0FlowIntegrationTest:154
- 审核记录（review_record 表，设计态、当前不存在）保存 reviewer、decision、reason、sourceVersion、createdAt；审核记录是追加事实而不是覆盖字段；撤回、归档、恢复通过状态或新版本表达，不能覆盖已发布题面和历史审核意见
- 状态转换表必须定义 `fromState + command + actor -> toState`；不存在的边返回 409，越权 actor 返回 403（统一 ApiException + GlobalExceptionHandler 契约）
- 乐观锁事实：@Version entity_version 真实存在于 ImportJob.java:19 与 PracticeSession（:19），**PaperVersion 没有 @Version**；悲观行锁真实例子是 PracticeService.submit 的 SELECT…FOR UPDATE
- 外部源码索引（MustRemember）：[Spring StateMachine](https://docs.spring.io/spring-statemachine/docs/current/reference/) 的 state/event/transition 配置模型（项目未引入该依赖，状态机为手写迁移方法）

# MustUnderstand

- 审核是状态机、权限和追加审计的组合；解析只产生候选，人工决定是否进入正式题库；候选层、review_record、ARCHIVED 均未实现，不能与真实 DRAFT→PUBLISHED 路径混为一谈
- 并发审核需要 `entity_version` 检查或悲观行锁，旧页面不能覆盖新决定，冲突返回 409；项目内同型防护可参照提交幂等三层（Redis 锁→FOR UPDATE→唯一键）与同键并发只出一份结果的测试 M0FlowIntegrationTest:179
- "同事务写事实、afterCommit 做外部副作用"是项目已落地范式（Outbox 同事务入库 + 提交后 evict 缓存），审计外发事件应套用同一时机，回滚的动作不产生审计
- 外部源码索引（MustUnderstand）：[Spring StateMachine guards/actions](https://docs.spring.io/spring-statemachine/docs/current/reference/#sm-guards) 的守卫与副作用边界
- 官方：[Spring StateMachine](https://spring.io/projects/spring-statemachine)
