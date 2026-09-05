# MustRemember

- `PracticeSession` 的事实字段是 `studentId`、`paperVersionId`、`status`、`totalScore`、`submissionKey`、`submissionResultJson`、`createdAt` 和 `submittedAt`
- `SubmissionItem` 以 `(sessionId, questionVersionId)` 定位答案；数据库联合唯一键配合 `findBySessionIdAndQuestionVersionId` 实现重复保存更新同一行
- 保存答案的调用顺序是 `requireSession(user, sessionId) -> status == IN_PROGRESS -> requireQuestion(session, questionId) -> normalize(answer) -> save`
- 答案覆盖保存的源码骨架是：
  ```java
  SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, questionId)
      .map(old -> { old.replaceAnswer(normalized); return old; })
      .orElseGet(() -> new SubmissionItem(sessionId, questionId, normalized));
  return submissions.save(item);
  ```
- 源码索引（MustRemember）：[PracticeController.java](../../src/main/java/com/allen/questionbank/controller/PracticeController.java) 第 25-39 行的 create/save/submit 入口；[PracticeService.java](../../src/main/java/com/allen/questionbank/service/PracticeService.java) 第 40-50 行的答案覆盖保存

# MustUnderstand

- 会话绑定具体版本而非题库，保证题目集合、标准答案和分值稳定
- 资源授权和状态机是两个独立条件：用户拥有会话仍不能修改 `SUBMITTED` 会话
- 会话状态转换必须在实体/Service 内统一维护；Controller 不能根据客户端传来的 `status` 或 `studentId` 决定结果
- 源码索引（MustUnderstand）：[PracticeSession.java](../../src/main/java/com/allen/questionbank/entity/PracticeSession.java) 第 9-19 行的状态/版本字段；[PracticeSession.java](../../src/main/java/com/allen/questionbank/entity/PracticeSession.java) 第 35-38 行的提交状态方法；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 49-74 行的外键和答案唯一键
