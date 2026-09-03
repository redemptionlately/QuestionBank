# 必须会背会写

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
- 源码索引（会背会写）：[BankController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankController.java) 第 58-65 行的 `QuestionInput`；[BankService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankService.java) 第 35-52 行的版本创建和题目循环

# 必须理解

- 历史练习引用具体 `paper_version_id`，新版本只能追加，不能改变已经引用的题面和答案
- 客户端提交的分数、角色、题目 ID 和题目归属都不可信；服务端用当前用户、会话版本和数据库标准答案重新判定
- 源码索引（必须理解）：[QuestionVersion.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/QuestionVersion.java) 第 6-35 行的版本字段；[V1__m0_schema.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V1__m0_schema.sql) 第 35-47 行的约束
