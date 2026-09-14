# Day43 SQL Advanced · 题目与标准解答（Solutions）

> MySQL InnoDB（本地 compose 为 mysql:8.4，真 MySQL 证据环境为 9.0.x），结合本项目真实表（question_version、practice_session、wrong_question、paper_version 等，共 11 张 Flyway 表）与真实证据测试 MysqlRealDbIntegrationTest（MYSQL_EVIDENCE 门控）、MyBatis 错题查询。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
优化顺序固定：**确认语义 → 收集执行计划（EXPLAIN / EXPLAIN ANALYZE）→ 定位瓶颈 → 单变量改写 → 再次验证**，不能只凭"加索引"跳过语义与数据分布。项目内对应真实资产：[V2__m0_query_indexes.sql](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql) 三条读路径索引、[scripts/sql/seed_papers.sql](../../scripts/sql/seed_papers.sql) 的 5000/500 确定性数据 + ANALYZE TABLE、MysqlRealDbIntegrationTest 的 EXPLAIN 对账用例。

---

### Q2. 写并验证 Top-N per group。
每版取前 10 题（按题号）：
```sql
WITH ranked AS (
    SELECT q.*,
           ROW_NUMBER() OVER (PARTITION BY paper_version_id ORDER BY question_no) AS rn
    FROM question_version q
)
SELECT * FROM ranked WHERE rn <= 10;
```
- `ROW_NUMBER()`：同序也强制 1,2,3（不并列、不跳号）；
- `RANK()`：并列后跳号（1,1,3）；`DENSE_RANK()`：并列不跳号（1,1,2）；
- 分区聚合示例：`SUM(score) OVER (PARTITION BY student_id)` 保留每行并附学生总分。
- 项目真实 CTE 用例：[seed_papers.sql](../../scripts/sql/seed_papers.sql) 用递归 CTE `seq(n)` 生成 1..5000（会话需 `cte_max_recursion_depth`≥5000，默认 1000 会在 1001 行报 ERROR 3636）；真实分页/排序 SQL 在 [resources/mapper/WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml)（ORDER BY last_wrong_at DESC, question_version_id DESC + LIMIT/OFFSET）。

---

### Q3. 定位慢查询。
同时看五样：① `EXPLAIN`/`EXPLAIN ANALYZE` 的访问类型（type）、实际选中 key；② 实际扫描行数（rows/actual rows）；③ 锁等待；④ 最终返回行数（扫描很多只返回很少说明索引差）；⑤ 数据分布与统计信息（`ANALYZE TABLE`，seed 脚本末尾即执行）。常见索引失效：对索引列套函数（`WHERE DATE(created_at)=...`）、隐式类型转换（字符串列传数字）、前导模糊 `LIKE '%x'`、不满足最左前缀。
**项目真实对照测试**：MysqlRealDbIntegrationTest.explainHitsCompositeIndexAndFunctionWrappedPredicateLosesIt（:135）——同一谓词直接比较命中 V2 复合索引、用函数包裹列后 EXPLAIN 失去该索引；M0FlowIntegrationTest.queryIndexesExistForPublishedAndStudentReadPaths（:215，H2 侧断言三条读路径索引存在）。V2 索引：idx_paper_version_status_published_at(status,published_at)、idx_practice_session_student_created_at(student_id,created_at)、idx_wrong_question_student_last_wrong_at(student_id,last_wrong_at)。

---

### Q4. 写出窗口函数、CTE、EXISTS、MVCC、gap lock、死锁检测的 SQL/并发示例。
```sql
-- CTE 命名中间结果（注意 app 单体练习完成状态是 COMPLETED，不是 cloud 的 SUBMITTED）
WITH submitted AS (SELECT * FROM practice_session WHERE status='COMPLETED')
SELECT student_id, COUNT(*) FROM submitted GROUP BY student_id;

-- EXISTS 表达“存在关联”，找到至少完成过一次练习的学生（比 IN 更直观，优化器常转 semi-join）
SELECT u.id, u.username FROM user_account u
WHERE EXISTS (SELECT 1 FROM practice_session p WHERE p.student_id = u.id);
```
**MVCC（多版本并发控制）**：InnoDB 用隐藏列（trx_id、roll_ptr）+ undo log 构造一致性读视图（ReadView）。RC 下每条 SELECT 生成新视图（不可重复读），RR 下事务内第一次快照读建立视图、之后一致；快照读不加锁，当前读（`SELECT ... FOR UPDATE`、UPDATE/DELETE）读最新版本并加锁。项目证据：MysqlRealDbIntegrationTest.repeatableReadKeepsSnapshotUntilCommit（:186，RR 事务内快照保持到提交）、readCommittedSeesNewlyCommittedValue（:205，RC 立即可见已提交值）。
**锁**：record lock（锁索引记录）、gap lock（锁记录间间隙防插入，RR 下）、next-key lock（record+gap，默认范围锁，防幻读）。项目证据：forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout（:227，PracticeService.submit 的 SELECT…FOR UPDATE 让第二写者等到锁等待超时）。
**死锁检测**：InnoDB 默认 `innodb_deadlock_detect=ON`，发现循环等待立即回滚代价较小的事务并报 1213；应用应捕获并重试（幂等前提下）。项目证据：deadlockDetectedAndVictimRolledBack（:259，制造死锁并验证牺牲者回滚）。

---

### Q5. 给出函数包列、隐式转换、循环锁顺序导致的三个问题。
1. **函数包列**：`WHERE LOWER(username)='admin'` 使 username 索引无法按有序值定位 → 全表扫描；改写为 `username='admin'`（依赖排序规则）或建函数索引/生成列；项目内由 :135 用例直接对照"裸谓词命中 vs 函数包裹失效"；
2. **隐式类型转换**：`varchar_col = 123` 时 MySQL 把列转数字，等于在列上套函数 → 索引失效；应 `varchar_col = '123'`；
3. **循环锁顺序**：事务 A 先锁行 1 再锁行 2，事务 B 先锁行 2 再锁行 1 → 死锁（:259 用例复现，InnoDB 回滚牺牲者报 1213）。解决：**全局统一加锁顺序**（如都按主键升序锁）、缩短事务、降低持锁范围、有限次重试；应用侧还有 RedisGuardedPracticeSubmitter 在同 session 提交进入事务前先串行化，降低行锁碰撞概率。

---

## External

### E1. 比较窗口函数与相关子查询。
- 相关子查询：对外层每行重新执行一次子查询，写 Top-N 常需要自连接/嵌套，可读性差、可能多次扫描；
- 窗口函数：一次扫描内用 OVER 计算，保留原行、可同时取多个分析值，通常执行更高效、语义更清晰。现代 MySQL 8 优先用窗口函数；老版本或极复杂条件才退回相关子查询，并用执行计划对比。项目内"复杂只读查询"统一交给 MyBatis（WrongQuestionQueryMapper：动态 `<if>` 片段 filters、search 分页、summarize 用 COUNT/SUM/MAX + COALESCE 兜底），与 JPA 写入路径双栈共存，聚合正确性由 WrongQuestionQueryMapperTest.summarizeMatchesManualAggregationWithAndWithoutFilter（:119）与真 MySQL 对账用例 :368 锁死。

### E2. 测 NULL 与时区。
- **NULL**：`NULL = NULL` 结果是 NULL（未知），判空用 `IS NULL`；聚合 `COUNT(col)` 忽略 NULL、`COUNT(*)` 不忽略；窗口/排序中 NULL 顺序（MySQL 默认 ASC 时 NULL 在前）要显式处理；唯一键允许 NULL 且多 NULL 不冲突（项目唯一键如 uk_submission_question、uk_outbox_dedup 的列均 NOT NULL，不依赖该行为）；
- **时区**：[application.yml:5](../../app/src/main/resources/application.yml) 数据源 URL 显式带 `serverTimezone=UTC`，实体用 `Instant` 映射 UTC 时间戳。不要依赖数据库/服务器本地时区做业务换算；存储统一 UTC，展示层按用户时区转换，跨时区排序/按天统计才不会错位。
- 补充边界：ShardingSphere 分库分表只以 **test scope** 存在（sharding/ShardingEvidenceTest 6 证据：路由分布、单分片下推、跨片聚合归并、广播表、绑定表 join、唯一键边界），生产主 classpath 不含、主线仍是 JPA 单库 + MyBatis 只读查询，不得说成已做分库分表。
