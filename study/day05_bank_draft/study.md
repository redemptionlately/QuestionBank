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
- 源码索引（MustRemember）：[BankController.java](../../src/main/java/com/allen/questionbank/controller/BankController.java) 第 60-63 行的 `QuestionInput`；[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 38-55 行的版本创建和题目循环

# MustUnderstand

- 历史练习引用具体 `paper_version_id`，新版本只能追加，不能改变已经引用的题面和答案
- 客户端提交的分数、角色、题目 ID 和题目归属都不可信；服务端用当前用户、会话版本和数据库标准答案重新判定
- 源码索引（MustUnderstand）：[QuestionVersion.java](../../src/main/java/com/allen/questionbank/entity/QuestionVersion.java) 第 9-24 行的版本字段与构造器；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 35-47 行的约束
