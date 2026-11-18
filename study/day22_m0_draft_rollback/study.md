# MustRemember

- `createDraft`（BankService:44-62）的事务顺序是 `find bank -> check owner -> 非空校验 -> calculate nextVersion(max+1) -> save PaperVersion -> validate/save each QuestionVersion`，整个方法一个 @Transactional
- 题目校验（validateQuestion :129-142）包括题干非空、type 非空（仅 SINGLE/MULTIPLE/TRUE_FALSE）、选项非空且不重复、答案非空不重复且属于选项、score>0、SINGLE/TRUE_FALSE 恰好单答案
- 任意一题在同一 `@Transactional` 中失败时，PaperVersion 和此前已保存的 QuestionVersion 一起回滚；save ≠ commit
- 回滚测试的核心断言是（M0FlowIntegrationTest:154-177，方法名 invalidQuestionRollsBackTheWholeDraftTransaction）：
  ```java
  // MockMvc 提交 [合法题, 非法题] 草稿 → 400
  assertEquals(papersBefore, papers.count());
  assertEquals(questionsBefore, questions.count());
  ```
- 源码索引（MustRemember）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 44-62 行草稿事务、129-142 行校验；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 21-47 行的唯一键/外键（uk 在 :30/:45）

# MustUnderstand

- 应用校验提供清晰错误（400 INVALID_INPUT），唯一键/外键是并发和绕过应用校验后的最终防线；两层都需要
- `max(version_no)+1` 在并发下可能竞态，联合唯一键只能拒绝冲突，不能自动合并两个草稿；友好做法是捕获冲突重试取号或父行加锁串行化
- 回滚成立的前提是异常经过事务代理：self-invocation、catch 吞异常、受检异常默认不回滚三类反例由 TransactionFailureModeTest 固化
- 源码索引（MustUnderstand）：[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 154-177 行的回滚断言；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 129-142 行业务校验、64-81 行发布状态机
