# Day24 M0 Practice Grading · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `PracticeService` 判分循环（:67-85）/canonical（:163-172）、`SubmissionItem`、结果 record（:208-213）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[PracticeController.java:49-56](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java) 提交接口（强制 `@RequestHeader("Idempotency-Key")`，可选 Redis 锁守卫 :52-54）；[PracticeService.java:56-89](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 提交事务（判分循环 :70-84、汇总 :85-88）、`:132-172` 选项校验/normalize/canonical；[SubmissionItem.java:6-31](../../app/src/main/java/com/allen/questionbank/practice/SubmissionItem.java)（uk_submission_question 在 :7，replaceAnswer :29、grade :30）。

---

### Q2. 运行三题型测试。
对应 `M0FlowIntegrationTest.gradesAllQuestionTypesAndLocksSubmittedPractice`（:104，默认运行）：SINGLE/MULTIPLE/TRUE_FALSE 各出题，提交后断言每题 correct/score、totalScore=各对题分值之和、maxScore=全卷分值之和，并断言提交后再 PUT 答案得到 409（练习锁定）。

---

### Q3. 提交后 PUT 返回 409。
`saveAnswer`（PracticeService:43-54）首先 `requireSession`（存在+归属，:45），随后 :46 判断 `status != IN_PROGRESS` 即抛 409 `STATE_CONFLICT`「练习已提交，答案不可修改」；:47 的 requireQuestion 还校验题目属于会话绑定版本（不属于也 409）。SUBMITTED 是终态，保证判分依据冻结。

---

### Q4. 写出缺答 `[]`、多选排序规范化、total/max 计算与 Session/Item 持久化规则。

真实判分循环（PracticeService:70-85）：
```java
int total = 0;
List<GradedAnswer> graded = new ArrayList<>();
for (QuestionVersion question : paperQuestions) {
    // 缺答：找不到 SubmissionItem 时用空答案 "[]"（:71-72）
    SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, question.getId())
            .orElseGet(() -> new SubmissionItem(sessionId, question.getId(), "[]"));
    boolean correct = canonical(item.getAnswerJson()).equals(canonical(question.getAnswerJson())); // :73
    int score = correct ? question.getScore() : 0;
    item.grade(score, correct);                 // :75 写逐题 score/correct（SubmissionItem:30）
    submissions.save(item);                     // :76
    total += score;                             // :82 对题分值累加
}
int maxScore = paperQuestions.stream().mapToInt(QuestionVersion::getScore).sum(); // :85
```
- **缺答 `[]`**：canonical(`[]`)=`[]`，与非空标准答案不等 → 0 分；
- **多选排序无关**：canonical（:163-172）把 JSON 数组每个元素 `value.toString()` 收集后 `Comparator.naturalOrder()` 排序再比较，`["C","A"]` 与 `["A","C"]` 相等；必须集合完全相等（无缺选、无多选）才得分；canonical **只排序不去重**；
- **totalScore**：正确题 score 之和；**maxScore**：全部题 score 之和（:85），都由服务端计算，客户端无法提供或覆盖。

---

### Q5. 写出 SubmitResult 与 GradedAnswer record，说明 Session/Item/WrongQuestion 写入关系。

真实 record（PracticeService:208-209）：
```java
public record GradedAnswer(Long questionId, boolean correct, int score) {}
public record SubmitResult(Long sessionId, int totalScore, int maxScore, List<GradedAnswer> answers) {}
```
**同一提交事务内的三类写入（PracticeService:56-89，同成败）**：
1. **SubmissionItem（逐题）**：每题一行（uk_submission_question(session_id,question_version_id) 在 SubmissionItem:7 / V1:72），`grade(score,correct)` 记录该题得分与对错；已存在的答案行被复用（saveAnswer 阶段 replaceAnswer 更新）；
2. **PracticeSession（汇总）**：:86 `session.submit(idempotencyKey,total,write(result))`（实体方法 PracticeSession:35-38）置 SUBMITTED、写 submissionKey/totalScore/submittedAt，并把 SubmitResult 序列化为 `submission_result_json` 快照（write :193-196，供幂等重放 readResult :198-201）；
3. **WrongQuestion（错题聚合）**：:77-81 仅对判错题，按 (studentId,questionVersionId) 查：存在则 `markWrong()` 计数+1 刷新时间，不存在则新建（uk_wrong_question 兜底）。
写入顺序在同一 `@Transactional`（:56），且入口先经 `findByIdForUpdate` 行锁（:61）：任一失败全部回滚，不会出现“总分写了但逐题没写”的不一致。错题本的**读侧**查询（筛选/分页/聚合）不由 JPA 负责，而走 MyBatis WrongQuestionQueryMapper。

---

## External

### E1. 增加重复答案规则。
对学生提交 `["A","A"]` 明确策略：当前 canonical 只排序不去重（:169-170），因此与标准答案 `[A]` 不相等 → 判错；saveAnswer 阶段的 validateChoices（:132-141）只校验“选项属于题目”和“必须是数组”，也不去重。若产品希望“重复元素视为手误”，应在 normalize 阶段先 `distinct()` 再排序，并补三类测试（重复但集合对→对、重复且含错误选项→错、空数组→错），规则必须显式锁定，不能依赖默认行为；注意老师侧出题校验 BankService.validateQuestion:139 已要求标准答案无重复。

### E2. 说明主观题为什么不进 M0。
M0 判分是**确定性客观规则**：答案集合可与标准答案精确比较（canonical 后字符串相等），结果可复现、可自动判分、可把结果序列化后幂等重放且字节一致。题型枚举只有 SINGLE/MULTIPLE/TRUE_FALSE（[QuestionType.java:3-7](../../app/src/main/java/com/allen/questionbank/bank/QuestionType.java)）。主观题（简答/编程/论述）没有唯一标准答案，需要独立评分模型、人工复核、部分分、评分留痕与申诉，属于不同领域能力；贸然塞进 M0 会破坏“服务端确定性判分 + 幂等重放一致”的前提，因此明确排除，后续以独立模块设计。
