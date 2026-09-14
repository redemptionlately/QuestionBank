# MustRemember

- 错题聚合的写入分支是：
  ```java
  wrongQuestions.findByStudentIdAndQuestionVersionId(studentId, questionId)
      .ifPresentOrElse(WrongQuestion::markWrong,
          () -> wrongQuestions.save(new WrongQuestion(studentId, questionId)));
  ```
- `WrongQuestion` 以 `(studentId, questionVersionId)` 唯一；`wrongCount` 累计错误次数，`lastWrongAt` 记录最近错误时间
- 查询按当前学生强制过滤（studentId 取自认证身份），MyBatis 动态 SQL 支持 wrongCountMin/wrongSince 可选筛选、分页与 COUNT/SUM/MAX 聚合，按 `lastWrongAt DESC, questionVersionId DESC` 稳定排序；同一个已持久化提交结果的幂等重放不再次进入聚合
- 双持久层分工：错题写入走 JPA（WrongQuestionRepository），复杂只读查询走 MyBatis（WrongQuestionQueryMapper + XML，namespace 二级缓存 60s）
- 源码索引（MustRemember）：[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 77-81 行的错题写入分支、第 105-115 行 wrongBook；[WrongQuestion.java](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestion.java) 第 17-20 行的构造与计数更新；[WrongQuestionQueryMapper.java](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionQueryMapper.java) 第 17-30 行的 search/summarize；[WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml) 全文 42 行动态 SQL 与二级缓存

# MustUnderstand

- 错题是判分派生事实，不修改标准答案、题目版本和正式分数
- 使用题目版本 ID 防止不同发布版本的相同题号混淆；做对后是否移除属于产品规则，不是判分必然结果
- 源码索引（MustUnderstand）：[WrongQuestionRepository.java](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionRepository.java) 第 6-8 行的等值唯一查询（写入侧）；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 77-86 行的唯一键/外键；MyBatis 二级缓存零 SQL 由 WrongQuestionQueryMapperTest.secondIdenticalSummarizeHitsSecondLevelCacheWithoutSql 固化
