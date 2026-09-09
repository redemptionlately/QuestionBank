# Day24 M0 Practice Grading · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `PracticeService` 判分循环/canonical、`SubmissionItem`、结果 record。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PracticeController` 33-35 行提交接口；`PracticeService` 63-80 行判分循环、114-137 行选项校验与 canonical；`SubmissionItem` 18-30 行逐题状态。

---

### Q2. 运行三题型测试。
对应 `gradesAllQuestionTypesAndLocksSubmittedPractice`：SINGLE/MULTIPLE/TRUE_FALSE 各出题，提交后断言每题 correct/score、totalScore=各对题分值之和、maxScore=全卷分值之和，并断言提交后再 PUT 答案得到 409。

---

### Q3. 提交后 PUT 返回 409。
`saveAnswer` 首先 `requireSession`（存在+归属），随后判断 `status != IN_PROGRESS` 即抛 409 `STATE_CONFLICT`「练习已提交，答案不可修改」。SUBMITTED 是终态，保证判分依据冻结。

---

### Q4. 写出缺答 `[]`、多选排序规范化、total/max 计算与 Session/Item 持久化规则。

```java
int totalScore = 0;
for (QuestionVersion q : paperQuestions) {
    // 缺答：找不到 SubmissionItem 时用空答案 "[]"
    SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, q.getId())
        .orElseGet(() -> new SubmissionItem(sessionId, q.getId(), "[]"));
    boolean correct = canonical(item.getAnswerJson()).equals(canonical(q.getAnswerJson()));
    int score = correct ? q.getScore() : 0;
    item.grade(score, correct);                 // 写逐题 score/correct
    submissions.save(item);
    totalScore += score;                        // 对题分值累加
}
int maxScore = paperQuestions.stream().mapToInt(QuestionVersion::getScore).sum();
```
- **缺答 `[]`**：canonical(`[]`)=`[]`，与非空标准答案不等 → 0 分；
- **多选排序无关**：canonical 把 JSON 数组元素转字符串后 `Comparator.naturalOrder()` 排序再比较，`["C","A"]` 与 `["A","C"]` 相等；必须集合完全相等（无缺选、无多选）才得分；
- **totalScore**：正确题 score 之和；**maxScore**：全部题 score 之和，都由服务端计算，客户端无法提供或覆盖。

---

### Q5. 写出 SubmitResult 与 GradedAnswer record，说明 Session/Item/WrongQuestion 写入关系。

```java
public record SubmitResult(Long sessionId, int totalScore,
                           int maxScore, List<GradedAnswer> answers) {}
public record GradedAnswer(Long questionId, boolean correct, int score) {}
```
**同一提交事务内的三类写入（同成败）**：
1. **SubmissionItem（逐题）**：每题一行（uk session+question），`grade(score,correct)` 记录该题得分与对错；
2. **PracticeSession（汇总）**：`submit(key,totalScore,resultJson)` 置 SUBMITTED、写 submissionKey、totalScore、submittedAt，并把 SubmitResult 序列化为 `submission_result_json` 快照（供幂等重放）；
3. **WrongQuestion（错题聚合）**：仅对判错题，按 (studentId,questionVersionId) 查：存在则 `markWrong()` 计数+1，不存在则新建。
写入顺序在同一 `@Transactional`：任一失败全部回滚，不会出现“总分写了但逐题没写”的不一致。

---

## External

### E1. 增加重复答案规则。
对学生提交 `["A","A"]` 明确策略：当前 canonical 只排序不去重，因此与标准答案 `[A]` 不相等 → 判错。若产品希望“重复元素视为手误”，应在 `normalize` 阶段先 `distinct()` 再排序，并补三类测试（重复但集合对→对、重复且含错误选项→错、空数组→错），规则必须显式锁定，不能依赖默认行为。

### E2. 说明主观题为什么不进 M0。
M0 判分是**确定性客观规则**：答案集合可与标准答案精确比较，结果可复现、可自动判分。主观题（简答/编程/论述）没有唯一标准答案，需要独立评分模型、人工复核、部分分、评分留痕与申诉，属于不同领域能力；贸然塞进 M0 会破坏“服务端确定性判分 + 幂等重放字节一致”的前提，因此明确排除在 M0 范围外，后续以独立模块设计。
