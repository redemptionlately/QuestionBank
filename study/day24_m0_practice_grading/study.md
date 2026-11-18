# MustRemember

- `create` 只接受 `PUBLISHED` 版本（requirePublished，未发布 409 STATE_CONFLICT）；`saveAnswer` 只接受 `IN_PROGRESS` 会话（:46，提交后改答案 409），并检查题目属于会话绑定版本（requireQuestion :126-130）
- `submit`（PracticeService:56-89）遍历试卷全部题目，缺少 `SubmissionItem` 使用 `[]`（:71-72）；答案 JSON 经 canonical 排序规范化后与标准答案规范化值比较（:163-172，只排序不去重）
- `totalScore = 正确题 score 之和`，`maxScore = 全部题目 score 之和`（:85）；客户端不提供也不能覆盖正式分数字段
- 结果 DTO 的源码形态是（:208-209）：
  ```java
  public record GradedAnswer(Long questionId, boolean correct, int score) {}
  public record SubmitResult(Long sessionId, int totalScore, int maxScore, List<GradedAnswer> answers) {}
  ```
- 源码索引（MustRemember）：[PracticeController.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 49-56 行的提交接口；[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 70-85 行的判分循环

# MustUnderstand

- 提交事务（:56，入口先 findByIdForUpdate 行锁 :61）同时写逐题 Item、Session 总结果（含 submission_result_json 快照）和 WrongQuestion 聚合，保存的 JSON 使异键/同键重试返回一致结果
- 题型策略属于领域规则；M0 只有 SINGLE/MULTIPLE/TRUE_FALSE 三种全对/全错客观规则（QuestionType.java:3-7），主观题需要独立评分和人工复核
- 错题写入走 JPA（判分路径 :77-81），错题读取走 MyBatis 动态 SQL（WrongQuestionQueryMapper + XML），双持久层按“写/JPA、复杂只读/MyBatis”分工
- 源码索引（MustUnderstand）：[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 132-172 行的选项校验和 canonical 规则、208-213 行 record；[SubmissionItem.java](../../app/src/main/java/com/allen/questionbank/practice/SubmissionItem.java) 第 6-31 行（uk :7、grade :30）
