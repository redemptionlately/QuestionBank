# Day10 Wrong Book · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PracticeService` 错题分支、`WrongQuestion`、V1 表结构。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（主代码在 `app/src/main/java/com/allen/questionbank/`，XML 在 `app/src/main/resources/mapper/`）：写入侧——[practice/PracticeService.java:77-81](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 判分循环中的错题写入分支；[practice/WrongQuestion.java:17-20](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestion.java) 构造与 markWrong 计数/时间更新；[practice/WrongQuestionRepository.java:7](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionRepository.java) 唯一查询（JPA，只写不读列表）。读取侧——[practice/PracticeService.java:105-115](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) wrongBook（分页校验 + 调 MyBatis Mapper）；[practice/WrongQuestionQueryMapper.java:17-30](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionQueryMapper.java) search/summarize 接口；[resources/mapper/WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml) 动态 SQL（filters 片段 :21-25、search :27-33、summarize :35-41、namespace 二级缓存 :12）；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 77-86 行唯一键/外键。

---

### Q2. 第一次答错创建一条错题记录。

判分循环中，某题判错时执行（真实代码 [PracticeService.java:77-81](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java)）：
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
查到已有记录走 `markWrong()`（[WrongQuestion.java:20](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestion.java)）：
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

错题列表查询已由 **MyBatis 动态 SQL** 接管（JPA 只负责写入侧），支持可选筛选、分页与聚合。Controller 真实代码（[PracticeController.java:61-70](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java)）：
```java
@GetMapping("/wrong-questions")
public PracticeService.WrongBook wrongQuestions(
        @RequestParam(required = false) Integer wrongCountMin,          // 可选：错误次数下限
        @RequestParam(required = false) @DateTimeFormat(iso = DATE_TIME) Instant wrongSince, // 可选：时间下限
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
    return service.wrongBook(CurrentUser.require(), wrongCountMin, wrongSince, page, size);
}
```
Service（[PracticeService.java:105-115](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java)）：studentId **强制取自认证身份**，客户端传不进来；分页非法（page<0、size 超出 1-200）返回 400；分别调 mapper.search 与 mapper.summarize：
```java
@Transactional(readOnly = true)
public WrongBook wrongBook(AuthPrincipal user, Integer wrongCountMin, Instant wrongSince, int page, int size) {
    if (page < 0) throw bad("page 不能为负");
    if (size < 1 || size > 200) throw bad("size 必须在 1-200 之间");
    List<WrongQuestionRow> items = wrongQuestionQuery.search(user.userId(), wrongCountMin, wrongSince, size, page * size);
    WrongQuestionSummary summary = wrongQuestionQuery.summarize(user.userId(), wrongCountMin, wrongSince);
    return new WrongBook(summary, items, page, size);
}
```
XML 动态 SQL（[WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml)）：`<sql id="filters">` 里 `WHERE student_id = #{studentId}` 是必填的资源归属 SQL 层兜底，两个 `<if>` 只在参数非空时拼接；search 按 `last_wrong_at DESC, question_version_id DESC` 稳定排序并 `LIMIT/OFFSET` 分页；summarize 用 `COUNT/SUM/MAX`（COALESCE 兜底 0）一次返回 totalQuestions/totalWrongCount/maxWrongCount。namespace 上配了 `<cache size=512 eviction=LRU flushInterval=60000 readOnly=false/>` 二级缓存（60s 弱一致窗口，命中返回序列化拷贝），由 `WrongQuestionQueryMapperTest.secondIdenticalSummarizeHitsSecondLevelCacheWithoutSql`（:133，借助自研 monitor-starter 的 StatementHandler 层计数证明第二次零 SQL）等 8 个用例覆盖。

---

## External

### E1. 先答错后答对，记录当前 M0 是否保留错题并解释原因。
M0 错题只在**提交判错时新增/累加**，没有“答对就删除错题”的逻辑，因此之后某次练习做对，错题记录仍保留（可由产品决定是否加 `resolved` 标记或减计数，但这不是判分的必然结果）。原因：错题本是历史学习事实，“曾经错过”不应被一次做对抹掉；是否移除属于产品规则，需要显式设计而不是隐式删除。

### E2. 新建同题号的新版本，确认错题版本 ID 不混淆。
错题关联的是 `question_version_id`（题目**版本**主键），不是题号 questionNo。新版本是新的一行、新的 id；旧错题仍指向旧版本 id，新版本答错会新建另一行错题，两者不会因为“题号相同”而合并。

### E3. 删除题库后查询历史错题，确认外键策略和产品语义一致。
V1 中 `wrong_question.question_version_id` 外键引用 `question_version(id)`（V1:85），且版本不可变、产品上没有删除题目的 API，因此历史错题永远能追溯到当时题面。若未来允许删除题库，需要显式选择外键策略：`RESTRICT`（有错题引用则禁止删除，符合“保留历史”语义）、`ON DELETE CASCADE`（连带清除历史，一般不适合错题本）或软删除；不能依赖默认行为而不声明语义。注意错题读取走 MyBatis（WrongQuestionQueryMapper），写入走 JPA，双栈下表与外键仍是同一套 V1 结构。
