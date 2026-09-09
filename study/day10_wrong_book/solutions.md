# Day10 Wrong Book · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PracticeService` 错题分支、`WrongQuestion`、V1 表结构。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PracticeService` 52-85 行错题写入分支；`WrongQuestion` 17-25 行计数与时间更新；`WrongQuestionRepository` 唯一查询；V1 77-86 行唯一键/外键。

---

### Q2. 第一次答错创建一条错题记录。

判分循环中，某题判错时执行（真实代码）：
```java
if (!correct) {
    wrongQuestions.findByStudentIdAndQuestionVersionId(user.userId(), question.getId())
        .ifPresentOrElse(WrongQuestion::markWrong,                       // 已有 → 计数+1
            () -> wrongQuestions.save(new WrongQuestion(user.userId(), question.getId()))); // 首次 → 新建
}
```
`new WrongQuestion(studentId, questionVersionId)` 内部 `wrongCount=1、lastWrongAt=now`；表上 `uk_wrong_question(student_id, question_version_id)` 保证一个学生对同一题目版本只有一行。

---

### Q3. 同一学生再次答错同一题只增加 wrongCount。
查到已有记录走 `markWrong()`：
```java
public void markWrong() { wrongCount++; lastWrongAt = Instant.now(); }
```
只更新计数和最近错误时间，不插入新行。

---

### Q4. 相同幂等 key 重试不增加 wrongCount。
同 key 重放走 `readResult` 直接返回已存结果，不进入判分循环（见 Day09），错题聚合代码不会被执行第二次，因此计数稳定。

---

### Q5. 不同学生的同一题分别拥有独立错题记录。
唯一键是**联合**的 `(student_id, question_version_id)`：学生 A、B 答错同一题是两行不同记录（student_id 不同），各自独立计数、互不影响。

---

### Q6. `/api/wrong-questions` 只能返回当前学生记录。

真实代码：
```java
@GetMapping("/wrong-questions")
public List<WrongQuestionView> wrongQuestions() {
    return service.wrongQuestions(CurrentUser.require()).stream()
        .map(w -> new WrongQuestionView(w.getQuestionVersionId(), w.getWrongCount(), w.getLastWrongAt())).toList();
}
// Service：
public List<WrongQuestion> wrongQuestions(AuthPrincipal user) {
    return wrongQuestions.findByStudentIdOrderByLastWrongAtDesc(user.userId()); // 强制按当前用户过滤
}
```
查询条件写死为当前登录用户 id，客户端无法传 studentId 查别人的错题；排序按 `lastWrongAt DESC`（最近错的在前）。

---

## External

### E1. 先答错后答对，记录当前 M0 是否保留错题并解释原因。
M0 错题只在**提交判错时新增/累加**，没有“答对就删除错题”的逻辑，因此之后某次练习做对，错题记录仍保留（可由产品决定是否加 `resolved` 标记或减计数，但这不是判分的必然结果）。原因：错题本是历史学习事实，“曾经错过”不应被一次做对抹掉；是否移除属于产品规则，需要显式设计而不是隐式删除。

### E2. 新建同题号的新版本，确认错题版本 ID 不混淆。
错题关联的是 `question_version_id`（题目**版本**主键），不是题号 questionNo。新版本是新的一行、新的 id；旧错题仍指向旧版本 id，新版本答错会新建另一行错题，两者不会因为“题号相同”而合并。

### E3. 删除题库后查询历史错题，确认外键策略和产品语义一致。
V1 中 `wrong_question.question_version_id` 外键引用 `question_version(id)`，且版本不可变、产品上没有删除题目的 API，因此历史错题永远能追溯到当时题面。若未来允许删除题库，需要显式选择外键策略：`RESTRICT`（有错题引用则禁止删除，符合“保留历史”语义）、`ON DELETE CASCADE`（连带清除历史，一般不适合错题本）或软删除；不能依赖默认行为而不声明语义。
