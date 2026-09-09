#!/usr/bin/env bash
# 慢查询日志实操证据：开启 slow_query_log -> 跑低效查询 -> 从 mysql.slow_log 取回捕获记录 -> 还原配置。
# 回答面试题"线上发现慢 SQL 怎么排查"的第一步：慢日志不是摆设，是能配、能看、能定位的。
set -u
cd "$(dirname "$0")/.."
MYSQL="/c/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe"
export MYSQL_PWD="${MYSQL_PASSWORD:-783421}"
M=(-uroot -h127.0.0.1 -P3306 --protocol=tcp)
STAMP=$(date +%Y%m%d_%H%M)
OUT="output/slow_query_evidence_${STAMP}.log"

echo "=== 1/4 开启慢查询日志（全局阈值 1ms，TABLE 输出便于脚本读取）==="
echo "（注意读的是 @@global.*：SET GLOBAL 不影响当前会话，新连接才继承——这本身就是个面试考点）"
"$MYSQL" "${M[@]}" -e "
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.001;
SET GLOBAL log_output = 'TABLE';
SELECT @@global.slow_query_log AS slow_log_on, @@global.long_query_time AS threshold_s, @@global.log_output AS output;"

echo
echo "=== 2/4 清空 slow_log 后各跑一次：失效索引全表扫描 vs 正常索引查询 ==="
"$MYSQL" "${M[@]}" question_bank -e "TRUNCATE mysql.slow_log;" >/dev/null
echo "-- 故意失效：UPPER(status) 包裹 + 无 LIMIT 全表扫描 + filesort"
"$MYSQL" "${M[@]}" question_bank -e "
SELECT COUNT(*) FROM (SELECT id, title FROM paper_version WHERE UPPER(status)='PUBLISHED' ORDER BY published_at DESC) t;" 2>/dev/null
echo "-- 正常：谓词直接命中 idx_paper_version_status_published_at"
"$MYSQL" "${M[@]}" question_bank -e "
SELECT COUNT(*) FROM (SELECT id, title FROM paper_version WHERE status='PUBLISHED' ORDER BY published_at DESC) t;" 2>/dev/null
sleep 1

echo
echo "=== 3/4 读取 mysql.slow_log：只有慢查询被捕获，快查询不出现 ==="
"$MYSQL" "${M[@]}" -e "
SELECT LEFT(sql_text, 95) AS sql_captured,
       CONCAT(ROUND(query_time*1000, 1), ' ms') AS took,
       CONCAT(ROUND(lock_time*1000, 2), ' ms') AS lock_ms,
       rows_examined, rows_sent
FROM mysql.slow_log
ORDER BY start_time;" | tee "$OUT"

echo
echo "=== 4/4 还原配置（阈值 10s / 文件输出 / 日志关闭），避免影响线上语义 ==="
"$MYSQL" "${M[@]}" -e "
SET GLOBAL slow_query_log = OFF;
SET GLOBAL long_query_time = 10;
SET GLOBAL log_output = 'FILE';
SELECT @@global.slow_query_log AS restored_off, @@global.long_query_time AS restored_threshold;"
echo "证据已保存: $OUT"
