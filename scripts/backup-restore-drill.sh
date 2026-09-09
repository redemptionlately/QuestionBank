#!/usr/bin/env bash
# 备份恢复演练：mysqldump 全量备份 -> 恢复到独立校验库 -> 行数比对 -> 清理。
# 回答面试题"数据库备份恢复怎么做"的证据版：dump 能用、恢复后数据一致、且可重复执行。
set -u
cd /c/Project/QuestionBank
BIN="/c/Program Files/MySQL/MySQL Server 9.0/bin"
STAMP=$(date +%Y%m%d_%H%M)
DUMP="output/backup_question_bank_${STAMP}.sql"
export MYSQL_PWD="${MYSQL_PASSWORD:-783421}"
M=(-uroot -h127.0.0.1 -P3306 --protocol=tcp)

echo "=== 1/5 基线：记录源库关键表行数 ==="
"$BIN/mysql.exe" "${M[@]}" -N -e "
SELECT table_name, table_rows FROM information_schema.tables
WHERE table_schema='question_bank' AND table_name IN
('user_account','question_bank','paper_version','question_version','practice_session','outbox_event')
ORDER BY table_name;" | tee /tmp/baseline_rows.txt
cat /tmp/baseline_rows.txt

echo
echo "=== 2/5 mysqldump 全量备份（单事务一致性快照）==="
"$BIN/mysqldump.exe" "${M[@]}" --single-transaction --routines --triggers question_bank > "$DUMP" 2>/dev/null
[ -s "$DUMP" ] || { echo "备份失败：dump 文件为空"; exit 1; }
echo "备份文件: $DUMP ($(du -h "$DUMP" | cut -f1))"

echo
echo "=== 3/5 恢复到独立校验库 question_bank_restore ==="
"$BIN/mysql.exe" "${M[@]}" -e "DROP DATABASE IF EXISTS question_bank_restore; CREATE DATABASE question_bank_restore CHARACTER SET utf8mb4;"
"$BIN/mysql.exe" "${M[@]}" question_bank_restore < "$DUMP"
echo "恢复完成"

echo
echo "=== 4/5 一致性比对：源库 vs 恢复库逐表行数 ==="
FAIL=0
while IFS=$'\t' read -r table; do
  table="${table//$'\r'/}"   # mysql -N 输出带 CRLF，\r 混进表名会静默失败
  actual=$("$BIN/mysql.exe" "${M[@]}" -N -B -e "SELECT COUNT(*) FROM question_bank.\`$table\`;")
  restored=$("$BIN/mysql.exe" "${M[@]}" -N -B -e "SELECT COUNT(*) FROM question_bank_restore.\`$table\`;")
  status="OK"
  if [ -z "$actual" ] || [ -z "$restored" ]; then
    status="QUERY-FAILED"; FAIL=1   # 两边都查不到行数时绝不能判定"一致"
  elif [ "$actual" != "$restored" ]; then
    status="MISMATCH"; FAIL=1
  fi
  printf '%-22s source=%-8s restored=%-8s %s\n' "$table" "${actual:-ERR}" "${restored:-ERR}" "$status"
done < <("$BIN/mysql.exe" "${M[@]}" -N -B -e "
SELECT table_name FROM information_schema.tables
WHERE table_schema='question_bank' AND table_name NOT LIKE 'flyway%' ORDER BY table_name;")
[ "$FAIL" = "0" ] && echo "全部一致：恢复库与源库逐表行数完全相同" || { echo "!!! 存在不一致或查询失败，需排查"; exit 1; }

echo
echo "=== 5/5 清理校验库 ==="
"$BIN/mysql.exe" "${M[@]}" -e "DROP DATABASE question_bank_restore;"
echo "校验库已删除（备份文件保留: $DUMP）"
[ "$FAIL" = "0" ]
