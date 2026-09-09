# Day22 M0 Draft Rollback · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `BankService.createDraft/validateQuestion`、集成测试 153-176 行。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankService` 35-53 行草稿循环与校验入口、86-95 行业务校验；V1 21-47 行唯一键/外键；`M0FlowIntegrationTest` 153-176 行回滚断言。

---

### Q2. 运行 rollback 测试。
对应 `invalidDraftRollsBackEntireVersion`：构造 `[合法题, 非法题]` 调 createDraft，断言抛 `ApiException`，且数据库中 PaperVersion、QuestionVersion 都没有新增。本机无 Maven 时用 IDE 运行该测试方法；测试环境 H2 + Flyway，`@Transactional` 方法抛 RuntimeException 触发回滚。

---

### Q3. 新增非法题型测试。
最小补测：构造 `correctAnswers=["X"]` 而 `options=["A","B"]`（答案不在选项中），断言：
```java
assertThatThrownBy(() -> service.createDraft(admin, bankId, "t", List.of(bad)))
    .isInstanceOf(ApiException.class);
assertThat(papers.findByBankIdOrderByVersionNoDesc(bankId)).isEmpty();
assertThat(questions.findAll()).isEmpty();   // 连版本带题目整体不存在
```
可参数化覆盖：题干空白、options 空、选项重复、答案重复、SINGLE 多答案、score=0，每一种都必须回滚且不残留。

---

### Q4. 写出题干、选项、答案、分值和联合唯一键的校验规则。

真实 `validateQuestion` 规则（任一不满足即 400 `INVALID_INPUT`）：
1. `prompt` 非 null 且非空白（题干非空）；
2. `type` 非 null；
3. `options` 非空，且**无重复**（`distinct().count()==size()`）；
4. `correctAnswers` 非空、**无重复**，且 `options.containsAll(correctAnswers)`（答案必须是选项子集）；
5. `score > 0`（正数分值，DTO 层 `@Min(1)` 再兜一层）；
6. SINGLE、TRUE_FALSE 恰好 1 个正确答案，MULTIPLE 可多个。

联合唯一键（数据库最终防线）：
- `uk_paper_version_no(bank_id, version_no)`；
- `uk_question_version_no(paper_version_id, question_no)`。

---

### Q5. 写出事务回滚断言，证明 PaperVersion 与此前 QuestionVersion 同时不存在。

createDraft 的执行顺序：`find bank → check owner → nextVersion → save(PaperVersion) → for each { validateQuestion; save(QuestionVersion) }`，整个方法在一个 `@Transactional` 内。
```java
// 即使 PaperVersion 和第 1 题已经 save（进入持久化上下文/可能已 flush），
// 第 2 题校验抛异常 → 代理 rollback → 全部撤销
assertThat(papers.findAll()).isEmpty();
assertThat(questions.findAll()).isEmpty();
```
关键点：`save` 不等于已提交；事务回滚会撤销同一事务内所有已执行的 INSERT/UPDATE，保证不会出现“版本头存在但题目残缺”的脏聚合。

---

## External

### E1. 并发草稿版本。
两个事务同时算 `max(version_no)+1` 可能得到相同号：应用层无法仅靠“先查后插”避免。`uk_paper_version_no` 让后提交者收到 `DataIntegrityViolationException`（唯一键只拒绝、不自动合并）。要更友好可：捕获唯一键冲突后重试取新号，或对父题库行加锁/用数据库序列，使版本号分配串行化。

### E2. 设计草稿编辑边界。
DRAFT 阶段是否允许改？建议规则：① 只有 owner 可编辑；② 仅 DRAFT 可增删改题目，编辑同样在单事务内整体替换/校验；③ 一旦 PUBLISHED 即冻结，任何修改只能新建版本；④ 编辑操作也要满足完整 validateQuestion，不允许保存“半合法”草稿。这样既给草稿迭代空间，又保证发布版本不可变。
