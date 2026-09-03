# 必须会背会写

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
- 源码索引（会背会写）：[PracticeController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 21-35 行的 create/save/submit 入口；[PracticeService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 39-49 行的答案覆盖保存

# 必须理解

- 会话绑定具体版本而非题库，保证题目集合、标准答案和分值稳定
- 资源授权和状态机是两个独立条件：用户拥有会话仍不能修改 `SUBMITTED` 会话
- 会话状态转换必须在实体/Service 内统一维护；Controller 不能根据客户端传来的 `status` 或 `studentId` 决定结果
- 源码索引（必须理解）：[PracticeSession.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 8-19、35-39 行的状态/版本字段；[V1__m0_schema.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V1__m0_schema.sql) 第 49-74 行的外键和答案唯一键
