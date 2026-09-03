# 必须会背会写

- 判分循环的核心代码是：
  ```java
  for (QuestionVersion question : paperQuestions) {
      SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, question.getId())
          .orElseGet(() -> new SubmissionItem(sessionId, question.getId(), "[]"));
      boolean correct = canonical(item.getAnswerJson()).equals(canonical(question.getAnswerJson()));
      int score = correct ? question.getScore() : 0;
      item.grade(score, correct);
  }
  ```
- 服务端读取数据库标准答案和提交答案判分；客户端不能提交正式分数
- 数组 JSON 先解析、逐元素转字符串、自然排序后比较；多选 `["A","C"]` 与 `["C","A"]` 等价，空数组 `[]` 判错
- 正确题得题目分值，错误题得 0；`SubmissionItem` 保存逐题结果，`PracticeSession` 保存总分和序列化结果
- 源码索引（会背会写）：[PracticeService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 63-85 行的判分/总分/错题循环；第 123-136 行的 JSON canonical 化

# 必须理解

- 规范化负责 JSON 语法、数组类型、元素排序和序列化一致性；选项归属、重复答案和损坏题目属于独立校验
- 主观题部分得分、模型建议和人工复核不属于 M0 的确定性规则
- 源码索引（必须理解）：[SubmissionItem.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/SubmissionItem.java) 第 6-31 行的逐题分数状态；[PracticeService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 114-121 行的选项归属校验
