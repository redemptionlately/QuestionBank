# MustRemember

- 窗口函数保留原行并计算 `ROW_NUMBER/RANK/SUM OVER`，CTE 用 `WITH` 命名中间结果（seed_papers.sql 递归 CTE 生成 5000 行，需调 cte_max_recursion_depth），`EXISTS` 表达关联记录存在性
- 慢查询分析同时看执行计划、实际扫描行数、锁等待、返回行数和数据分布；对索引列套函数或隐式类型转换会失去索引访问——真 MySQL 对照在 MysqlRealDbIntegrationTest:135（复合索引命中 vs 函数包裹失效，MYSQL_EVIDENCE 门控）
- V2__m0_query_indexes.sql 三条读路径索引：paper_version(status,published_at)、practice_session(student_id,created_at)、wrong_question(student_id,last_wrong_at)；H2 侧存在性断言在 M0FlowIntegrationTest:215
- InnoDB MVCC 通过隐藏版本和 undo 读取一致性视图；RR 快照保持到提交（MysqlRealDbIntegrationTest:186）、RC 立即可见已提交（:205）；FOR UPDATE 当前读加行锁（:227 锁等待超时）；循环等待触发死锁检测、回滚牺牲者报 1213（:259）
- Top-N per group 常用 `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` 再过滤 `rn <= N`
- 复杂只读查询走 MyBatis 双栈：[WrongQuestionQueryMapper.java](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionQueryMapper.java) + resources/mapper/WrongQuestionQueryMapper.xml（动态 `<if>`、ORDER BY 双列、LIMIT/OFFSET、COUNT/SUM/MAX+COALESCE、namespace 二级缓存 60s）；8 用例在 WrongQuestionQueryMapperTest，真 MySQL 对账在 :368
- app 单体练习状态是 IN_PROGRESS/COMPLETED（PracticeStatus），不要与 cloud 的 ACTIVE/SUBMITTED 混用；数据源 URL 带 serverTimezone=UTC，统一存 UTC
- ShardingSphere 仅 **test scope**（ShardingEvidenceTest 6 证据，MYSQL_SHARDING_EVIDENCE 门控），生产不进 classpath，不得说成分库分表已上线
- Top-N SQL 形态是：
  ```sql
  WITH ranked AS (
      SELECT q.*, ROW_NUMBER() OVER (
          PARTITION BY paper_version_id ORDER BY question_no) rn
      FROM question_version q
  ) SELECT * FROM ranked WHERE rn <= 10;
  ```
- 外部源码索引（MustRemember）：[MySQL window functions](https://dev.mysql.com/doc/refman/8.4/en/window-functions.html) 的 `ROW_NUMBER/RANK/SUM OVER`；[WITH CTE](https://dev.mysql.com/doc/refman/8.4/en/with.html)

# MustUnderstand

- SQL 优化顺序是确认语义、收集计划、定位瓶颈、单变量改写、再次验证；不能只因"加索引"就跳过语义和数据分布（5000/500 种子 + ANALYZE TABLE 保证计划可复现）
- 死锁来自循环等待；统一锁顺序、缩短事务和有限重试比无限重试可靠，重试必须保证业务幂等（提交侧三层幂等：Redis 锁→FOR UPDATE→uk_submission_question）
- JPA 负责全部写入与等值读，MyBatis 只接管 wrong_question 的"全可选条件+分页+聚合"只读查询；两套持久层分工边界不能混
- 外部源码索引（MustUnderstand）：[InnoDB locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html) 的 record/gap/next-key lock 与死锁检测
- 官方：[MySQL InnoDB](https://dev.mysql.com/doc/refman/8.4/en/innodb-storage-engine.html)、[Window Functions](https://dev.mysql.com/doc/refman/8.4/en/window-functions.html)
