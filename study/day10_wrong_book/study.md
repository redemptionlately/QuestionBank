# MustRemember

- 错题聚合的写入分支是：
  ```java
  wrongQuestions.findByStudentIdAndQuestionVersionId(studentId, questionId)
      .ifPresentOrElse(WrongQuestion::markWrong,
          () -> wrongQuestions.save(new WrongQuestion(studentId, questionId)));
  ```
- `WrongQuestion` 以 `(studentId, questionVersionId)` 唯一；`wrongCount` 累计错误次数，`lastWrongAt` 记录最近错误时间
- 查询按当前学生过滤并按 `lastWrongAt DESC` 排序；同一个已持久化提交结果的幂等重放不再次进入聚合
- 源码索引（MustRemember）：[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 52-85 行的错题写入分支；[WrongQuestion.java](../../src/main/java/com/allen/questionbank/practice/WrongQuestion.java) 第 17-25 行的计数与时间更新

# MustUnderstand

- 错题是判分派生事实，不修改标准答案、题目版本和正式分数
- 使用题目版本 ID 防止不同发布版本的相同题号混淆；做对后是否移除属于产品规则，不是判分必然结果
- 源码索引（MustUnderstand）：[WrongQuestionRepository.java](../../src/main/java/com/allen/questionbank/practice/WrongQuestionRepository.java) 第 7-10 行的唯一查询；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 77-86 行的唯一键/外键
