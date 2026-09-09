# Day05 Bank Draft · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `BankService.createBank/createDraft/validateQuestion`、`BankController`、V1 表结构。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankController` 58-65 行 `QuestionInput` DTO；`BankService` 35-53 行版本创建与题目循环、86-95 行题目校验。

---

### Q2. 写出题库创建、草稿版本创建和题目校验的 Service 流程。

**解答（真实代码流程）**：
```java
// ① 创建题库：校验当前用户与名称 → 构造实体 → save
@Transactional
public QuestionBank createBank(AuthPrincipal user, String name, String description) {
    if (user == null || name == null || name.isBlank())
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "题库名称不能为空");
    return banks.save(new QuestionBank(user.userId(), name.trim(), description));
}

// ② 创建草稿版本：查题库 → 所有权 → 入参非空 → 计算下一版本号 → 存版本 → 循环校验并存题目
@Transactional
public PaperVersion createDraft(AuthPrincipal user, Long bankId, String title,
                                List<QuestionInput> inputs) {
    QuestionBank bank = banks.findById(bankId).orElseThrow(() -> notFound("题库不存在"));
    if (!bank.getOwnerId().equals(user.userId())) throw forbidden();
    if (title == null || title.isBlank() || inputs == null || inputs.isEmpty())
        throw bad("试卷标题和至少一道题目不能为空");

    int nextVersion = papers.findByBankIdOrderByVersionNoDesc(bankId).stream()
            .mapToInt(PaperVersion::getVersionNo).max().orElse(0) + 1;   // 同题库内递增
    PaperVersion paper = papers.save(new PaperVersion(bankId, nextVersion, title.trim(), user.userId()));

    for (int i = 0; i < inputs.size(); i++) {
        QuestionInput input = inputs.get(i);
        validateQuestion(input);                                          // 每题先校验
        questions.save(new QuestionVersion(paper.getId(), i + 1, input.prompt(), input.type(),
                json(input.options()), json(input.correctAnswers()), input.score(), input.explanation()));
    }
    return paper;
}

// ③ 题目字段校验
private void validateQuestion(QuestionInput input) {
    if (input == null || input.prompt() == null || input.prompt().isBlank() || input.type() == null
            || input.options() == null || input.options().isEmpty() || input.correctAnswers() == null
            || input.correctAnswers().isEmpty() || input.score() <= 0) throw bad("题目字段不合法");
    if (!input.options().containsAll(input.correctAnswers())) throw bad("标准答案必须来自选项");
    if (input.options().stream().distinct().count() != input.options().size()) throw bad("选项不能重复");
    if (input.correctAnswers().stream().distinct().count() != input.correctAnswers().size()) throw bad("标准答案不能重复");
    if (input.type() == QuestionType.SINGLE && input.correctAnswers().size() != 1) throw bad("单选题只能有一个答案");
    if (input.type() == QuestionType.TRUE_FALSE && input.correctAnswers().size() != 1) throw bad("判断题只能有一个答案");
}
```
流程要点：聚合层次是 `QuestionBank → PaperVersion → QuestionVersion`；题号 `questionNo` 从 1 开始固定顺序；整个创建在**同一事务**内，任何一题校验失败，前面已 save 的版本和题目一并回滚。

---

### Q3. 管理员创建题库和含单选/多选/判断题的草稿成功。

- 先 `POST /api/auth/login`（admin/admin123）拿 token；
- `POST /api/admin/banks`，body `{"name":"M0 Bank","description":"..."}` → 200，返回 bank.id；
- `POST /api/admin/banks/{id}/versions`，body 示例：
```json
{"title":"Typed Paper","questions":[
  {"prompt":"single","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":2},
  {"prompt":"multiple","type":"MULTIPLE","options":["A","B","C"],"correctAnswers":["A","C"],"score":5},
  {"prompt":"boolean","type":"TRUE_FALSE","options":["TRUE","FALSE"],"correctAnswers":["TRUE"],"score":3}
]}
```
→ 200，版本 status=DRAFT、versionNo 递增；SINGLE/TRUE_FALSE 恰好一个答案，MULTIPLE 可多个。

---

### Q4. 空题干、空选项、标准答案不属于选项分别返回 400。

| 非法情况 | 拦截位置 | code |
|---|---|---|
| prompt 为 null/空白 | `validateQuestion` 第一行 | 400 `INVALID_INPUT` |
| options 为 null/空 | 同上 | 400 `INVALID_INPUT` |
| correctAnswers 不是 options 子集 | `!options.containsAll(correctAnswers)` | 400 `INVALID_INPUT` |
| DTO 层就为空 | `@NotBlank/@NotEmpty` + `@Valid` | 400 `VALIDATION_ERROR` |
两层校验的 code 不同：注解绑定失败是 `VALIDATION_ERROR`，Service 业务规则失败是 `INVALID_INPUT`，都是 HTTP 400。

---

### Q5. 学生创建题库、非所有者编辑题库分别返回 403。

- **学生创建题库**：`@PreAuthorize("hasRole('ADMIN')")` 在方法进入前拦截，STUDENT 抛 `AccessDeniedException` → 403 `FORBIDDEN`；
- **非所有者（另一个 ADMIN）编辑**：角色通过，但 Service 内 `if (!bank.getOwnerId().equals(user.userId())) throw forbidden();` 拦截 → 403。
说明：角色是第一道粗粒度授权，资源所有权是第二道细粒度授权，两道都要存在。

---

### Q6. 解释为什么版本号和题号需要数据库唯一约束。

应用层“查最大版本号 +1 / 按 index 编号”在**并发创建**时两个事务可能读到同一个最大值，产生重复编号；唯一约束是最后防线：
- `uk_paper_version_no(bank_id, version_no)`：同一题库绝不允许两个相同版本号；
- `uk_question_version_no(paper_version_id, question_no)`：同一试卷绝不允许相同题号。
即使应用 bug 或并发导致重复，数据库会用 `DataIntegrityViolationException` 拒绝写入，保证“历史练习引用的版本/题号永远唯一稳定”。

---

## External

### E1. 两个版本并发创建，检查是否出现重复版本号。
两个事务同时 `findByBankIdOrderByVersionNoDesc` 都得到 max=N，都尝试插入 versionNo=N+1：没有唯一键时会出现两条 N+1；有 `uk_paper_version_no` 时后提交者被数据库拒绝（可捕获后重试取新号）。这是“应用计算 + 数据库约束”组合保证正确性的典型例子。

### E2. 尝试使用客户端提供的分数，确认 API 不接受该字段作为事实。
`QuestionInput` 中 `score` 是题目分值（管理员定义、且 `@Min(1)`）；学生侧提交答案的 DTO 中**没有分数字段**，判分一律由服务端读取 `QuestionVersion.score` 与标准答案重新计算。客户端即使多传 `totalScore` 也会被 Jackson 忽略，无法影响结果。原则：客户端提交的分数、角色、题目 ID 归属都不可信。

### E3. 查询旧版本，确认创建新版本不会修改旧题。
版本采用**只追加（append-only）不可变**模型：新建版本是插入新的 `paper_version` + 新的 `question_version` 行，从不 UPDATE 旧行；历史练习绑定的是具体 `paper_version_id`，因此永远看到当时的题面、答案和分值快照，不会被新版本影响。
