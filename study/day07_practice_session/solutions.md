# Day07 Practice Session · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PracticeService`、`PracticeSession`、`SubmissionItem`、V1 表结构。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PracticeController` 21-36 行 create/save/submit 入口；`PracticeService` 39-49 行答案覆盖保存；`PracticeSession` 状态字段与 `submit()`；V1 49-74 行外键与答案唯一键。

---

### Q2. 写出练习状态转换和保存答案的归属校验。

**状态机**：`IN_PROGRESS → SUBMITTED`（单向，由实体 `submit()` 内聚维护，客户端不能传 status）。

**保存答案调用顺序（真实代码）**：
```java
@Transactional
public SubmissionItem saveAnswer(AuthPrincipal user, Long sessionId, Long questionId, JsonNode answer) {
    PracticeSession session = requireSession(user, sessionId);                 // ① 存在 + 归属
    if (session.getStatus() != PracticeStatus.IN_PROGRESS)
        throw conflict("练习已提交，答案不可修改");                              // ② 状态校验
    QuestionVersion question = requireQuestion(session, questionId);           // ③ 题目属于本卷
    String normalized = normalize(answer);                                     // ④ 规范化
    validateChoices(question, answer);                                         // ⑤ 选项归属校验
    SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, questionId)
        .map(existing -> { existing.replaceAnswer(normalized); return existing; }) // 有则更新
        .orElseGet(() -> new SubmissionItem(sessionId, questionId, normalized));   // 无则新建
    return submissions.save(item);
}

private PracticeSession requireSession(AuthPrincipal user, Long sessionId) {
    PracticeSession session = sessions.findById(sessionId).orElseThrow(() -> notFound("练习不存在"));
    if (!session.getStudentId().equals(user.userId())) throw forbidden();       // 资源所有权
    return session;
}
private QuestionVersion requireQuestion(PracticeSession session, Long questionId) {
    QuestionVersion q = questions.findById(questionId).orElseThrow(() -> notFound("题目不存在"));
    if (!q.getPaperVersionId().equals(session.getPaperVersionId()))
        throw conflict("题目不属于当前练习");
    return q;
}
```
归属与状态是**两个独立条件**：拥有会话不代表能改已提交会话。

---

### Q3. 学生从已发布版本创建练习成功，从草稿创建返回 409。

```java
@Transactional
public PracticeSession create(AuthPrincipal user, Long paperId) {
    PaperVersion paper = banks.requirePublished(paperId);   // 内部要求 status=PUBLISHED，否则 409
    return sessions.save(new PracticeSession(user.userId(), paper.getId()));
}
```
`requirePublished` 对 DRAFT 抛 `STATE_CONFLICT`（409）「试卷版本尚未发布」。会话绑定的是**具体 paperVersionId**，不是题库 id。

---

### Q4. 保存当前试卷题目成功，保存其他试卷题目返回 409。
`requireQuestion` 比较 `question.paperVersionId == session.paperVersionId`：属于本卷才允许写；传入其他试卷的题目 id 时抛 409 `STATE_CONFLICT`「题目不属于当前练习」，防止把答案挂到错误的题目上。

---

### Q5. 同一题重复保存只保留一条 submission 行。
先 `findBySessionIdAndQuestionVersionId`：查到就 `replaceAnswer` 更新同一行，查不到才 new；再加上数据库唯一键 `uk_submission_question(session_id, question_version_id)` 双保险。因此同一题无论保存多少次，`submission_item` 中只有一行，答案以最后一次为准（UPSERT 语义）。

---

### Q6. 已提交会话再保存答案返回 409。
`session.getStatus() != IN_PROGRESS` 即拒绝（SUBMITTED 是终态），返回 409「练习已提交，答案不可修改」。这保证提交结果不可变，判分依据被冻结。

---

## External

### E1. 其他学生读取、保存和提交该会话都返回 403。
`requireSession` 中 `studentId` 与当前用户不一致直接抛 403 `FORBIDDEN`；view/saveAnswer/submit 全部经过它，所以他人对会话的读、写、提交都被拒绝。

### E2. 创建新试卷版本后读取旧练习，确认仍返回旧题目集合。
练习持有的是不可变的 `paper_version_id`，查询题目走 `findByPaperVersionIdOrderByQuestionNo`，永远返回该版本的题目；新版本是新的一组行，不影响旧练习，实现题面快照。

### E3. 发送不存在的题目 ID，确认返回 404 且数据库没有写入。
`questions.findById(questionId)` 为空 → 404 `NOT_FOUND`，方法在此中断，根本走不到 `submissions.save`，因此不会产生任何 submission 行；即使伪造一个存在但属于别卷的题 id，也会在下一步被 409 拦截。
