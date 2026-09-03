# 必须会背会写

- `create` 只接受 `PUBLISHED` 版本；`saveAnswer` 只接受 `IN_PROGRESS` 会话，并检查题目属于会话绑定版本
- `submit` 遍历纸卷全部题目，缺少 `SubmissionItem` 使用 `[]`；答案 JSON 规范化后与标准答案规范化值比较
- `totalScore = 正确题 score 之和`，`maxScore = 全部题目 score 之和`；客户端不提供也不能覆盖正式分数字段
- 结果 DTO 的源码形态是：
  ```java
  public record SubmitResult(Long sessionId, int totalScore,
                             int maxScore, List<GradedAnswer> answers) {}
  public record GradedAnswer(Long questionId, boolean correct, int score) {}
  ```
- 源码索引（会背会写）：[PracticeController.java](../../src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 33-35 行的提交接口；[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 63-85 行的判分循环

# 必须理解

- 提交事务同时写逐题 Item、Session 总结果和 WrongQuestion 聚合，保存的 JSON 使重试返回相同结果
- 题型策略属于领域规则；M0 的单选、多选、判断是全对/全错客观规则，主观题需要独立评分和人工复核
- 源码索引（必须理解）：[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 114-136 行的选项校验和 canonical 规则；[SubmissionItem.java](../../src/main/java/com/allen/questionbank/practice/SubmissionItem.java) 第 17-25 行的逐题状态
