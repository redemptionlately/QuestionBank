# MustRemember

- V2 的索引定义是：`(status,published_at)` 支持已发布试卷，`(student_id,created_at)` 支持学生练习，`(student_id,last_wrong_at)` 支持错题列表；V3/V4 另有 idx_import_job_owner_status、idx_outbox_pending 两个过程索引
- 联合索引遵守最左前缀；前导列等值过滤后，后续列可以用于范围或排序；跳过前导列通常不能完整利用索引
- `EXPLAIN.key` 是优化器实际选择的索引，`possible_keys` 是候选，`rows` 是估算扫描行数，`Extra` 可能显示排序、过滤和回表信息；实测日志 key_len=82/rows=2/Extra=Backward index scan
- 计划查询源码形态是：
  ```sql
  EXPLAIN SELECT id, title FROM paper_version
  WHERE status = 'PUBLISHED' ORDER BY published_at DESC;
  ```
- 源码索引（MustRemember）：[V2__m0_query_indexes.sql](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql) 第 2-9 行的索引定义；[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 215-220 行 queryIndexesExistForPublishedAndStudentReadPaths

# MustUnderstand

- 索引增加存储和写放大；选择性、数据量、统计信息和查询谓词共同决定优化器是否使用索引；唯一键 uk_* 首先是正确性约束，不是可选优化
- 真实计划绑定当前 MySQL、数据分布、SQL 和统计信息，不能从一次 EXPLAIN 推出固定 QPS 或普遍最优；函数包裹谓词会丢索引（MysqlRealDbIntegrationTest:135，MYSQL_EVIDENCE 门控）；灌数复测用 scripts/sql/seed_papers.sql（5000/500 行）
- 源码索引（MustUnderstand）：[mysql_explain_20260818.log](../../output/mysql_explain_20260818.log) 的真实 `key/rows/Extra`（:14-15）；[WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml) 第 27-33 行错题排序与索引对齐
- 关键证据：[V2__m0_query_indexes.sql](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql)、[mysql_explain_20260818.log](../../output/mysql_explain_20260818.log)；官方：[EXPLAIN](https://dev.mysql.com/doc/refman/8.4/en/explain.html)
