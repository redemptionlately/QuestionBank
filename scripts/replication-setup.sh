#!/usr/bin/env bash
# 读写分离实验拓扑一键复现：本机 3307 主 + 3308 从（GTID 复制，从库 super_read_only）。
# 与 3306 业务库完全隔离（独立 datadir/端口/库名 replica_demo）。
# 前置：无（mysqld 路径下方可改）。注意：两个 mysqld 是长驻进程，脚本退出不杀它们
# （Git Bash 下后台子进程会被随脚本杀掉，所以正式启动方式见脚本尾部提示）。
set -uo pipefail

cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/replication_setup_${STAMP}.log"
exec > >(tee "$OUT") 2>&1

MYSQLD="/c/Program Files/MySQL/MySQL Server 9.0/bin/mysqld.exe"
MYSQL="/c/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe"
BASEDIR="C:/Program Files/MySQL/MySQL Server 9.0"
DATA="C:/Project/QuestionBank/.workbuddy/mysql-repl"
PASSWORD="783421"

m() { "$MYSQL" -uroot -p"$PASSWORD" -h127.0.0.1 -P"$1" --protocol=tcp "${@:2}"; }

echo "=== 1/5 初始化双 datadir（幂等：已存在则跳过）==="
if [ ! -f "$DATA/master/mysql.ibd" ]; then
  mkdir -p "$DATA/master"
  "$MYSQLD" --no-defaults --initialize-insecure --datadir="$DATA/master" --basedir="$BASEDIR" || exit 1
else echo "master datadir 已存在，跳过"; fi
if [ ! -f "$DATA/slave/mysql.ibd" ]; then
  mkdir -p "$DATA/slave"
  "$MYSQLD" --no-defaults --initialize-insecure --datadir="$DATA/slave" --basedir="$BASEDIR" || exit 1
else echo "slave datadir 已存在，跳过"; fi

echo "=== 2/5 启动检查 ==="
# 探活必须带密码：实例已设 root 密码，无密码连接会被拒。
# 历史坑：这里曾用无密码探活，结果明明在运行的实例被误判"未启动"而整段跳过复制配置。
mysql_up() { # $1=端口：先带密码，再兼容首次 initialize-insecure 的无密码
  "$MYSQL" -uroot -p"$PASSWORD" -h127.0.0.1 -P"$1" --protocol=tcp -e "SELECT 1" >/dev/null 2>&1 && return 0
  "$MYSQL" -uroot            -h127.0.0.1 -P"$1" --protocol=tcp -e "SELECT 1" >/dev/null 2>&1 && return 0
  return 1
}
mysql_up 3307 || {
  echo "主库 3307 未启动或无响应。请执行：./scripts/start-evidence-env.sh start"
  exit 1
}
mysql_up 3308 || {
  echo "从库 3308 未启动或无响应。请执行：./scripts/start-evidence-env.sh start"
  exit 1
}
echo "双实例在位"

echo "=== 3/5 密码/复制账号/业务库 ==="
"$MYSQL" -uroot -h127.0.0.1 -P3307 --protocol=tcp -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$PASSWORD';" 2>/dev/null || true
"$MYSQL" -uroot -h127.0.0.1 -P3308 --protocol=tcp -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '$PASSWORD';" 2>/dev/null || true
# 注意：探针表一律放独立的 repl_probe_db。replica_demo 由 Flyway 管理
# （application-read-replica.yml 里 spring.flyway.enabled=true），在里面留任何手工表
# 都会让迁移以"非空库无 schema history"失败——历史上 repl_probe 就挡过一次。
m 3307 -e "CREATE USER IF NOT EXISTS 'repl'@'127.0.0.1' IDENTIFIED BY 'repl-pass-3307'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'127.0.0.1'; CREATE DATABASE IF NOT EXISTS replica_demo CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS repl_probe_db CHARACTER SET utf8mb4; DROP TABLE IF EXISTS replica_demo.repl_probe;"

echo "=== 4/5 配置从库复制（幂等：已配置则跳过）==="
REPL_STATUS="$(m 3308 -e "SHOW REPLICA STATUS\G" 2>/dev/null | grep -c "Replica_IO_Running")"
if [ "$REPL_STATUS" = "0" ]; then
  m 3308 -e "CHANGE REPLICATION SOURCE TO SOURCE_HOST='127.0.0.1', SOURCE_PORT=3307, SOURCE_USER='repl', SOURCE_PASSWORD='repl-pass-3307', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;"
else echo "复制已配置"; fi

echo "=== 5/5 验证 ==="
sleep 5
m 3308 -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running:|Replica_SQL_Running:"
m 3307 repl_probe_db -e "CREATE TABLE IF NOT EXISTS repl_probe (id INT PRIMARY KEY, mark VARCHAR(40)); INSERT INTO repl_probe VALUES (1,'setup-verification') ON DUPLICATE KEY UPDATE mark=VALUES(mark);"
for i in $(seq 1 50); do
  C="$(m 3308 -N repl_probe_db -e "SELECT COUNT(*) FROM repl_probe WHERE id=1;" 2>/dev/null)"
  [ "$C" = "1" ] && break
  sleep 0.2
done
echo "复制收敛判定: 从库可见 repl_probe_db.repl_probe (polls=$i)"
m 3308 -e "SELECT @@port AS slave_port, @@read_only AS ro, @@super_read_only AS sro;"
echo "证据日志: $OUT"
echo "跑证据测试: READ_REPLICA_EVIDENCE=true MYSQL_EVIDENCE=true ./scripts/mvn.sh test -Dtest=ReadReplicaRoutingIntegrationTest"
