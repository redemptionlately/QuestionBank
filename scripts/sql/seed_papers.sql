-- 压测与 EXPLAIN 用的确定性种子数据：5000 份试卷，其中 500 份已发布。
-- 目的：让优化器有足够行数选择索引，也让压测读路径有真实数据量。
-- 数据可用 scripts/sql/cleanup_seed.sql 清理。

-- 默认 cte_max_recursion_depth=1000 会让递归在 1001 次时中断（ERROR 3636），必须先抬高。
SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO question_bank (owner_id, name, description, status)
SELECT 1, 'LOADTEST-BANK', 'load test seed', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM question_bank WHERE name = 'LOADTEST-BANK');

SET @bank := (SELECT id FROM question_bank WHERE name = 'LOADTEST-BANK');

INSERT INTO paper_version (bank_id, version_no, title, status, created_by, created_at, published_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000)
SELECT @bank,
       100000 + n,
       CONCAT('压测试卷-', n),
       IF(n <= 500, 'PUBLISHED', 'DRAFT'),
       1,
       NOW(3),
       IF(n <= 500, NOW(3) - INTERVAL n MINUTE, NULL)
FROM seq
ON DUPLICATE KEY UPDATE title = VALUES(title);

ANALYZE TABLE paper_version;

SELECT COUNT(*) AS total_papers FROM paper_version WHERE bank_id = @bank;
SELECT COUNT(*) AS published_papers FROM paper_version WHERE bank_id = @bank AND status = 'PUBLISHED';
