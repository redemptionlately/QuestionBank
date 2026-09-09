# MustRemember

- `createDraft` 的事务顺序是 `find bank -> check owner -> calculate nextVersion -> save PaperVersion -> validate/save each QuestionVersion`
- 题目校验包括题干非空、选项非空且不重复、答案非空且属于选项、SINGLE/TRUE_FALSE 单答案、score 为正数
- 任意一题在同一 `@Transactional` 中失败时，PaperVersion 和此前已保存的 QuestionVersion 一起回滚
- 回滚测试的核心断言是：
  ```java
  assertThatThrownBy(() -> service.createDraft(user, bankId, "bad", List.of(valid, invalid)))
      .isInstanceOf(ApiException.class);
  assertThat(papers.findByBankIdOrderByVersionNoDesc(bankId)).isEmpty();
  ```
- 源码索引（MustRemember）：[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第 35-53 行的草稿循环与校验入口；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 21-47 行的唯一键/外键

# MustUnderstand

- 应用校验提供清晰错误，唯一键/外键是并发和绕过应用校验后的最终防线；两层都需要
- `max(version_no)+1` 在并发下可能竞态，联合唯一键只能拒绝冲突，不能自动合并两个草稿
- 源码索引（MustUnderstand）：[M0FlowIntegrationTest.java](../../src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 153-176 行的回滚断言；[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第 86-95 行的业务校验
