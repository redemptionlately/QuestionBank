# Day22 M0 Draft Rollback · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `BankService.createDraft/validateQuestion`（:44-62 / :129-138）、集成测试 M0FlowIntegrationTest.invalidQuestionRollsBackTheWholeDraftTransaction（:154-177）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[BankService.java:44-62](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 草稿事务（nextVersion 计算 :52-53、循环内先 validateQuestion 再 save :55-60）、`:129-138` validateQuestion 业务校验；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) :21-33 paper_version（uk_paper_version_no 在 :30）、:35-47 question_version（uk_question_version_no 在 :45）；[M0FlowIntegrationTest.java:154-177](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 回滚断言。

---

### Q2. 运行 rollback 测试。
对应 `M0FlowIntegrationTest.invalidQuestionRollsBackTheWholeDraftTransaction`（:154，test profile、H2+Flyway，默认运行）：通过 MockMvc 提交两题草稿——第 1 题合法、第 2 题是 SINGLE 却给了两个正确答案，接口返回 400；随后断言 `papers.count()` 与 `questions.count()` 都等于调用前（:175-176）。原理：`@Transactional` 方法抛 RuntimeException 系的 ApiException，事务代理回滚，同一事务内已执行的 INSERT 全部撤销。

---

### Q3. 新增非法题型测试。
最小补测：构造 `correctAnswers=["A","B"]` 而 SINGLE 只允许单答案（或答案不在选项中），断言：
```java
assertThatThrownBy(() -> service.createDraft(admin, bankId, "t", List.of(bad)))
    .isInstanceOf(ApiException.class);
assertThat(papers.findByBankIdOrderByVersionNoDesc(bankId)).isEmpty();
assertThat(questions.findAll()).isEmpty();   // 连版本带题目整体不存在
```
可参数化覆盖（对应 validateQuestion :129-138 每个分支）：题干空白、type=null、options 空、选项重复、答案重复、答案不是选项子集、SINGLE/TRUE_FALSE 多答案、score≤0（DTO 层另有 `@Min(1)`/`@NotBlank`/`@NotEmpty` Bean Validation，BankController:58-61），每一种都必须回滚且不残留。

---

### Q4. 写出题干、选项、答案、分值和联合唯一键的校验规则。

真实 `validateQuestion`（BankService:129-138）规则（任一不满足即 400 `INVALID_INPUT`）：
1. `prompt` 非 null 且非空白（题干非空，:130）；
2. `type` 非 null（题型枚举仅 SINGLE/MULTIPLE/TRUE_FALSE 三个值，:130）；
3. `options` 非空，且**无重复**（`distinct().count()==size()`，:134）；
4. `correctAnswers` 非空、**无重复**（:135），且 `options.containsAll(correctAnswers)`（答案必须是选项子集，:133）；
5. `score > 0`（正数分值，:132；DTO 层 `@Min(1)` 再兜一层）；
6. SINGLE（:136）、TRUE_FALSE（:137）恰好 1 个正确答案，MULTIPLE 可多个。

联合唯一键（数据库最终防线，[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql)）：
- `uk_paper_version_no UNIQUE(bank_id, version_no)`（:30）；
- `uk_question_version_no UNIQUE(paper_version_id, question_no)`（:45）。

---

### Q5. 写出事务回滚断言，证明 PaperVersion 与此前 QuestionVersion 同时不存在。

createDraft 的真实执行顺序（BankService:44-62）：`find bank（:47）→ check owner（:48）→ 非空校验（:49-51）→ nextVersion=max(version_no)+1（:52-53）→ save(PaperVersion)（:54）→ for each { validateQuestion（:57）; save(QuestionVersion)（:58-59） }`，整个方法在一个 `@Transactional`（:44）内。
```java
// 集成测试真实断言形态（M0FlowIntegrationTest:162-176，HTTP 层计数前后相等）
int papersBefore = (int) papers.count();
int questionsBefore = (int) questions.count();
// ... MockMvc 提交含非法题的草稿，期望 400 ...
assertEquals(papersBefore, papers.count());
assertEquals(questionsBefore, questions.count());
```
关键点：`save` 不等于已提交；事务回滚会撤销同一事务内所有已执行的 INSERT/UPDATE，保证不会出现“版本头存在但题目残缺”的脏聚合。回滚规则本身（RuntimeException 经代理才回滚、自调用/吞异常/受检异常的反例）由 TransactionFailureModeTest 5 个用例逐条固化。

---

## External

### E1. 并发草稿版本。
两个事务同时算 `max(version_no)+1`（BankService:52-53）可能得到相同号：应用层无法仅靠“先查后插”避免。`uk_paper_version_no`（V1:30）让后提交者收到 `DataIntegrityViolationException`（唯一键只拒绝、不自动合并）。要更友好可：捕获唯一键冲突后重试取新号，或对父题库行加锁/用数据库序列，使版本号分配串行化。cloud/bank-service 的 publish 是同一模式的对照实现：saveAndFlush 撞 (bank_id,version) 唯一键时捕 DataIntegrityViolationException 转 409“同一题库正在并发发布，请重试”——但注意那是 cloud 独立演示代码，与 app 不共享。

### E2. 设计草稿编辑边界。
DRAFT 阶段是否允许改？建议规则：① 只有 owner 可编辑（app 现状 createDraft 已在 :48 做 owner 校验，不匹配抛 403 FORBIDDEN）；② 仅 DRAFT 可增删改题目，编辑同样在单事务内整体替换/校验；③ 一旦 PUBLISHED 即冻结（publish :69-70 的状态机：已发布幂等返回、非 DRAFT 抛 409 STATE_CONFLICT），任何修改只能新建版本；④ 编辑操作也要满足完整 validateQuestion，不允许保存“半合法”草稿。这样既给草稿迭代空间，又保证发布版本不可变。
