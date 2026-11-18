# MustRemember

- `QuestionBank -> PaperVersion -> QuestionVersion` 是 M0 的聚合层次；`PaperVersion.versionNo` 在同一题库内递增，`QuestionVersion.questionNo` 从 1 开始固定顺序
- 创建草稿的关键代码形态是：
  ```java
  QuestionBank bank = banks.findById(bankId).orElseThrow(() -> notFound());
  if (!bank.getOwnerId().equals(user.userId())) throw forbidden();
  PaperVersion paper = papers.save(new PaperVersion(bankId, nextVersion, title, user.userId()));
  for (int i = 0; i < inputs.size(); i++) {
      BankController.QuestionInput input = inputs.get(i);
      validateQuestion(input);
      questions.save(new QuestionVersion(paper.getId(), i + 1, input.prompt(), input.type(),
          json(input.options()), json(input.correctAnswers()), input.score(), input.explanation()));
  }
  ```
- `correctAnswers` 必须是 `options` 的子集；SINGLE/TRUE_FALSE 只有一个答案，MULTIPLE 可有多个答案
- `(bank_id, version_no)` 和 `(paper_version_id, question_no)` 联合唯一键分别防止版本号和题号重复
- 源码索引（MustRemember）：[BankController.java](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) 第 74-80 行的 record（QuestionInput 在 :76-77，题型枚举 QuestionType 只有 SINGLE/MULTIPLE/TRUE_FALSE）；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 44-62 行的版本创建和题目循环、第 129-142 行的 validateQuestion

# MustUnderstand

- 历史练习引用具体 `paper_version_id`，新版本只能追加，不能改变已经引用的题面和答案
- 客户端提交的分数、角色、题目 ID 和题目归属都不可信；服务端用当前用户、会话版本和数据库标准答案重新判定
- 源码索引（MustUnderstand）：[QuestionVersion.java](../../app/src/main/java/com/allen/questionbank/bank/QuestionVersion.java) 第 5-39 行的不可变题目快照实体（联合唯一约束在 :6、字段 :9-19）；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 35-47 行的 question_version 约束
