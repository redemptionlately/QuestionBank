# Day08 Deterministic Grading · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PracticeService.submit/canonical/validateChoices`、`SubmissionItem`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PracticeService` 52-85 行判分/总分/错题循环、123-136 行 JSON canonical 化、114-121 行选项归属校验；`SubmissionItem` 18-30 行逐题分数状态。

---

### Q2. 写出规范化数组、标准答案比较、总分累加的伪代码。

**真实判分循环**：
```java
int total = 0;
for (QuestionVersion question : paperQuestions) {
    // 没作答的题按空答案 "[]" 处理
    SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, question.getId())
        .orElseGet(() -> new SubmissionItem(sessionId, question.getId(), "[]"));

    boolean correct = canonical(item.getAnswerJson()).equals(canonical(question.getAnswerJson()));
    int score = correct ? question.getScore() : 0;     // 全对得分，否则 0（无部分分）
    item.grade(score, correct);
    submissions.save(item);
    if (!correct) { /* 归集错题：存在则 wrongCount++，不存在则新建 */ }
    total += score;
}
int maxScore = paperQuestions.stream().mapToInt(QuestionVersion::getScore).sum();
```
**canonical 规范化（顺序无关的关键）**：
```java
String canonical(String json) {
    JsonNode node = objectMapper.readTree(json);
    if (!node.isArray()) return node.toString();
    List<String> values = new ArrayList<>();
    node.forEach(v -> values.add(v.toString()));
    values.sort(Comparator.naturalOrder());   // 逐元素转字符串后自然排序
    return values.toString();
}
```
判分只依赖数据库里的**标准答案**与**题目分值**，输入是提交答案，输出是逐题对错/分数 + 总分/满分。

---

### Q3. 单选正确得分、错误得 0。
SINGLE 只有一个标准答案；提交与 canonical 后标准答案完全相等则 `score=question.getScore()`，否则 `score=0、correct=false`，并进入错题归集。

---

### Q4. 多选答案顺序改变仍判定相同。
学生提交 `["C","A"]`，标准答案 `["A","C"]`：canonical 都排序为 `["A", "C"]`，`equals` 为 true。**必须每个元素都一致且无多余元素**才全对（这是集合相等，不是子集）。

---

### Q5. 空答案、未知选项和错误答案不能得分。
- **空答案**：未作答用 `"[]"`，canonical 为 `[]`，与非空标准答案不等 → 0 分；
- **未知选项**：保存答案时 `validateChoices` 已保证每个选项属于该题（否则 400），判分时若出现题目选项外的值，集合不相等 → 0 分；
- **错误答案/少选/多选**：集合不完全相等 → 0 分（M0 无部分得分规则）。

---

### Q6. 客户端伪造分数字段不能改变服务端结果。
提交接口 `POST /practices/{id}/submit` 只接收路径 id 与 `Idempotency-Key` 请求头，**请求体不读取任何分数**；分数来自 `QuestionVersion.score`（管理员建题时定，`@Min(1)`），总分由服务端循环累加。多传 `totalScore` 字段会被 Jackson 忽略，无法影响判分。

---

## External

### E1. 重复答案元素如 `["A","A"]` 的行为要明确并测试。
- **保存阶段**：题目创建时标准答案去重校验（`correctAnswers.distinct().count() != size()` 拒绝）；学生答案 `validateChoices` 只校验“选项是否属于题目”，默认不主动去重。
- **判分阶段**：canonical 只排序不去重，`["A","A"]` 规范化后是 `[A, A]`，与标准答案 `[A]` 不相等 → 判错 0 分。
- 若产品希望宽容，可在 normalize 阶段 `distinct()` 后再比较；但行为必须显式定义并有测试锁定，不能依赖默认巧合。

### E2. 题目分值为 0 或负数时确认创建阶段拒绝。
`QuestionInput.score` 上有 `@Min(1)`（DTO 层 → 400 `VALIDATION_ERROR`），Service 层 `validateQuestion` 又判断 `input.score() <= 0`（→ 400 `INVALID_INPUT`）双保险，因此 0/负分题在**创建草稿时就被拒绝**，不会留到判分阶段，保证 maxScore 一定为正。

### E3. 损坏的答案 JSON 不应造成 500，应返回明确 400。
`normalize/canonical` 解析 JSON 时捕获 `JsonProcessingException`，统一转成 `ApiException(400, INVALID_INPUT, "答案格式不合法")`；`validateChoices` 中题目选项 JSON 损坏也转成 400「题目选项格式损坏」。因此外部坏输入被显式拦截为客户端错误，不会冒泡到兜底处理器变成 500。
