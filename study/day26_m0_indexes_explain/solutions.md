# Day26 M0 Indexes & EXPLAIN · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 V2 索引、`output/mysql_explain_20260818.log`（真实 MySQL EXPLAIN 证据，内容已逐行核对）、门控测试 MysqlRealDbIntegrationTest。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[app/src/main/resources/db/migration/V2__m0_query_indexes.sql:2-9](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql) 三个索引定义；三个 Repository 的真实读路径（PaperVersionRepository.findByStatusOrderByPublishedAtDesc、PracticeSessionRepository.findByStudentIdOrderByCreatedAtDesc :12、错题侧 MyBatis [WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml) 的 ORDER BY :31）；`M0FlowIntegrationTest.queryIndexesExistForPublishedAndStudentReadPaths`（:215-220，经 indexCount 助手查 INFORMATION_SCHEMA 断言索引存在）；真实日志 `output/mysql_explain_20260818.log`。V3/V4 另加两个过程索引：idx_import_job_owner_status(owner_id,status,updated_at)（V3:15，重试清扫扫描）、idx_outbox_pending(status,created_at)（V4:19，Relay 拉 PENDING）。

---

### Q2. 运行索引测试和真实 EXPLAIN，指出 key。
- 集成测试 `queryIndexesExistForPublishedAndStudentReadPaths`（:215，默认运行）查 `INFORMATION_SCHEMA` 断言三个索引各存在 1 个（H2 验证“结构存在”）；
- 真实 MySQL 上执行 EXPLAIN（mysql_explain_20260818.log:14-15 实测）：
```
EXPLAIN SELECT id,title FROM paper_version
WHERE status='PUBLISHED' ORDER BY published_at DESC;
-- type=ref
-- possible_keys=idx_paper_version_status_published_at
-- key=idx_paper_version_status_published_at   ← 优化器实际选中
-- key_len=82  ref=const  rows=2  filtered=100.00
-- Extra=Backward index scan                    ← 沿索引反向扫描完成排序，无 Using filesort
```
**实际 key = `idx_paper_version_status_published_at`**，且利用索引有序性反向扫描完成 `ORDER BY published_at DESC`，避免额外排序。重构后同一结论的可复跑自动化证据是 `MysqlRealDbIntegrationTest.explainHitsCompositeIndexAndFunctionWrappedPredicateLosesIt`（:135，门控 MYSQL_EVIDENCE 默认跳过）：它同时固化反面案例——谓词列被函数包裹时复合索引失效。

---

### Q3. 写出三组联合索引列顺序与最左前缀规则。

| 索引 | 列顺序 | 支撑的查询 |
|---|---|---|
| idx_paper_version_status_published_at | (status, published_at) | WHERE status=? ORDER BY published_at（BankService:102/108） |
| idx_practice_session_student_created_at | (student_id, created_at) | WHERE student_id=? ORDER BY created_at（Repository:12） |
| idx_wrong_question_student_last_wrong_at | (student_id, last_wrong_at) | WHERE student_id=? [AND wrong_count>=?] [AND last_wrong_at>=?] ORDER BY last_wrong_at DESC, question_version_id DESC（[WrongQuestionQueryMapper.xml:27-33](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml)） |

**最左前缀**：联合索引 (a,b) 先按 a 排序、a 相同时按 b 排序。因此：
- 前导列做等值过滤（a=?）后，后续列可直接用于范围/排序（b），无需 filesort；
- 跳过前导列（只查 b 不查 a）通常无法完整使用该索引；
- 列顺序设计原则：等值列在前、范围/排序列在后，高选择性列结合实际查询安排。错题查询的两个可选 `<if>` 谓词（wrongCountMin、wrongSince）都以前导 student_id 等值为前提，与索引顺序一致。

---

### Q4. 解释 possible_keys、rows、Extra。
- `possible_keys`：优化器认为**可能**用到的索引（候选集合）；
- `key`：最终**实际选择**的索引（为 NULL 表示全表扫描）；
- `key_len`：使用到的索引字节长度（日志中 82），可判断联合索引实际用了几列；
- `rows`：优化器**估算**需要扫描的行数（日志中 2，非精确值，依赖统计信息）；
- `filtered`：按 WHERE 条件过滤后剩余比例（日志中 100.00）；
- `Extra`：补充信息——`Using where`（回表后再过滤）、`Using filesort`（额外排序，需警惕）、`Using temporary`（临时表）、`Backward index scan`（索引反向扫描，日志实测值）、`Using index`（覆盖索引不回表）。

---

## External

### E1. 增加数据量后重复 EXPLAIN 计划。
当前日志 rows=2 是极小数据量下的结果，优化器在小表上甚至可能认为全表扫描更快而放弃索引。项目提供确定性压测种子 [scripts/sql/seed_papers.sql](../../scripts/sql/seed_papers.sql)（递归 CTE 向 paper_version 插 5000 行、其中 500 行 PUBLISHED，末尾 ANALYZE TABLE 刷新统计信息）；灌数后重新 EXPLAIN，观察 type/key/rows/Extra 是否变化（可用 [scripts/slow-query-evidence.sh](../../scripts/slow-query-evidence.sh) 复跑）。结论：执行计划绑定当前数据分布与统计信息，**不能从一次 EXPLAIN 推出固定 QPS 或普遍最优**，数据量显著变化后要重新评估。

### E2. 计算索引写成本。
索引不是免费的：
- **存储成本**：每个二级索引是一棵独立 B+Tree，占用磁盘/内存（Buffer Pool）；
- **写放大**：每次 INSERT 要在表聚簇索引 + 每个相关二级索引各插入一项；UPDATE 索引列还要维护旧/新索引项；DELETE 同步删除；
- **维护成本**：页分裂、合并带来额外随机 I/O。
权衡：读多写少、查询确实走该索引时才建；为从不作为过滤/排序条件的列建索引是纯负担。本项目 V2 三个索引精确对应三条真实列表查询，V3/V4 的两个过程索引精确对应重试清扫与 Outbox 轮询，属于有依据的最小索引集；各表的唯一键（uk_*）同时承担正确性约束，不属于“可选优化索引”。
