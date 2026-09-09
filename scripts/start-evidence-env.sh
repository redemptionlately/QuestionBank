#!/usr/bin/env bash
# 证据环境一键自愈：Redis(6379) / Kafka(9092, KRaft 单机) / MySQL 主从(3307 主 + 3308 从 super_read_only)
#
# 用法：
#   ./scripts/start-evidence-env.sh start    # 只拉起缺失组件（已运行的跳过，幂等）
#   ./scripts/start-evidence-env.sh status   # 只体检，不启动
#   ./scripts/start-evidence-env.sh stop     # 干净关闭（保留 datadir，下次免初始化重启）
#
# 生命周期边界（诚实说明，别指望它跨会话长驻）：
#   组件以脚本后台子进程方式启动，随"当前终端会话"存在——会话/机器休眠结束后进程会被回收，
#   这是 Git Bash 下无法用 cmd start / PowerShell Start-Process 脱离启动所致（本机安全策略均拦截）。
#   因此本脚本的定位是"一条命令把环境从零恢复到可复现状态"，而不是"开机自启的常驻服务"：
#   新会话里重跑一次即可，全部步骤幂等（已运行的跳过、datadir 存在则免初始化、Kafka 按需 format）。
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT_MSYS="$(pwd)"
ROOT_WIN="$(pwd -W 2>/dev/null || echo 'C:/Project/QuestionBank')"

REDIS_EXE='C:/Users/Allen/tools/redis/Redis-8.10.1-Windows-x64-msys2/redis-server.exe'
MYSQLD='C:/Program Files/MySQL/MySQL Server 9.0/bin/mysqld.exe'
MYSQL_BASE='C:/Program Files/MySQL/MySQL Server 9.0'
MYSQL_EXE='C:/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe'
KAFKA_MSYS='/c/Users/Allen/tools/kafka/kafka_2.13-4.0.0'
KAFKA_CFG='C:/Users/Allen/tools/kafka/kafka_2.13-4.0.0/config/server.properties'
DATA='C:/Project/QuestionBank/.workbuddy/mysql-repl'
PASSWORD='783421'
# cloud 三服务专用 DB 用户的统一密码（与三个 application.yml 的 ${CLOUD_DB_PASSWORD:默认值} 对称；
# 本脚本 set -u，必须在这里给默认值——环境变量可整体覆盖演示默认）
CLOUD_DB_PASSWORD="${CLOUD_DB_PASSWORD:-qb-cloud-demo-pw}"
PIDDIR="$ROOT_MSYS/.workbuddy/pids"
mkdir -p "$ROOT_MSYS/output" "$PIDDIR"

listening() { netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q LISTENING; }

bg_start() { # $1=pid 文件名，其余=命令（调用方负责重定向输出与 &）
  local name="$1"; shift
  "$@" &
  echo $! > "$PIDDIR/$name.pid"
  disown 2>/dev/null || true
}

wait_port() { # $1=端口 $2=最长等待秒 $3=名称
  local i
  for ((i=0; i<$2; i++)); do
    if listening "$1"; then echo "  [ok]   $3 就绪（端口 $1，${i}s）"; return 0; fi
    sleep 1
  done
  echo "  [FAIL] $3 超时未监听端口 $1（${2}s）"
  return 1
}

mysql_ok() { # $1=端口：先带密码探，再无密码探（兼容首次 initialize-insecure）
  "$MYSQL_EXE" -uroot -p"$PASSWORD" -h127.0.0.1 -P"$1" --protocol=tcp -e "SELECT 1" >/dev/null 2>&1 && return 0
  "$MYSQL_EXE" -uroot            -h127.0.0.1 -P"$1" --protocol=tcp -e "SELECT 1" >/dev/null 2>&1 && return 0
  return 1
}

wait_mysql() { # $1=端口 $2=最长秒 $3=名称
  local i
  for ((i=0; i<$2; i++)); do
    if listening "$1" && mysql_ok "$1"; then echo "  [ok]   $3 就绪（端口 $1，${i}s）"; return 0; fi
    sleep 1
  done
  echo "  [FAIL] $3 超时未就绪（端口 $1，${2}s）"
  return 1
}

# log.dirs 的 Windows JVM 视角路径（见 start_kafka 里的"视角陷阱"注释）：
# 从 server.properties 解析 log.dirs，前缀相机当前盘符形成 C:/tmp/... 形态。
kafka_log_dirs_win() {
  local posix drive
  posix="$(grep -E '^log\.dirs=' "$KAFKA_CFG" 2>/dev/null | tail -1 | cut -d= -f2 | tr -d '\r ')"
  posix="${posix:-/tmp/kraft-combined-logs}"
  drive="$(echo "$ROOT_WIN" | cut -d: -f1)"
  echo "${drive}:${posix}"
}

# C:/tmp/x -> /c/tmp/x（Git Bash 只有后者能被 rm -rf 可靠删掉，见 KAFKA_FRESH 注释）
win_to_posix() {
  local drive letter rest
  drive="${1%%:*}"
  letter="$(echo "$drive" | tr 'A-Z' 'a-z')"
  rest="${1#*:}"          # /tmp/kraft-combined-logs
  echo "/$letter$rest"    # /c/tmp/kraft-combined-logs
}

start_redis() {
  if listening 6379; then echo "  [skip] Redis 已在运行"; return 0; fi
  bg_start redis "$REDIS_EXE" --port 6379 --bind 127.0.0.1 --appendonly no > "$ROOT_MSYS/output/redis.log" 2>&1
  wait_port 6379 25 "Redis"
}

start_kafka() {
  if listening 9092; then echo "  [skip] Kafka 已在运行"; return 0; fi
  # Kafka 数据目录的"视角陷阱"（本机踩过的真 bug，别改回去）：
  # server.properties 的 log.dirs=/tmp/kraft-combined-logs 是 POSIX 写法，Git Bash 的 /tmp 映射到 %TEMP%，
  # 而 Windows JVM 把 /tmp/... 解析成 "<当前盘>:\tmp\..."（本机即 C:/tmp/kraft-combined-logs）。
  # 判断目录是否存在必须按 JVM 视角——按 Git Bash 的 /tmp 判断永远为假，于是每轮都误报"被清空"，
  # 真正的脏元数据（旧 broker 注册 + 旧 consumer group）跨轮累积，最终引爆
  # DUPLICATE_BROKER_REGISTRATION → coordinator NotLeaderOrFollower → Java CLI 无限重连。
  local logdir_win
  logdir_win="$(kafka_log_dirs_win)"
  if [ "${KAFKA_FRESH:-false}" = "true" ]; then
    # Git Bash 里必须删 /c/tmp/... 形态：写成 C:/tmp/... 会被当成工作目录下的相对路径，
    # rm 静默失败（实测踩过，脏元数据一个没少）。
    echo "  [info] KAFKA_FRESH=true：清空 $logdir_win（从脏 KRaft 元数据恢复的唯一可靠手段）"
    rm -rf "$(win_to_posix "$logdir_win")" 2>/dev/null || true
  fi
  if [ -d "$logdir_win" ]; then
    echo "  [info] Kafka log.dirs 存在（JVM 视角 $logdir_win），仍执行 format（--ignore-formatted 幂等）"
  else
    echo "  [info] Kafka log.dirs 缺失，执行全新 format"
  fi
  local cid
  # 本机实测：Kafka CLI 的 JVM 冷启动约 73s（Windows + Git Bash），timeout 必须留足余量，
  # 否则会在正常启动路径上被误杀（历史 bug：timeout 60 直接掐掉了本可成功的 random-uuid）。
  cid="$(timeout 180 "$KAFKA_MSYS/bin/kafka-storage.sh" random-uuid 2>/dev/null | tail -1 | tr -d '\r\n ')"
  [ -n "$cid" ] || { echo "  [FAIL] 无法生成 cluster id，检查 Kafka 安装：$KAFKA_MSYS"; return 1; }
  "$KAFKA_MSYS/bin/kafka-storage.sh" format -t "$cid" -c "$KAFKA_CFG" --ignore-formatted >/dev/null 2>&1
  ( cd "$KAFKA_MSYS" && ./bin/kafka-server-start.sh "$KAFKA_CFG" \
      > "$ROOT_MSYS/output/kafka_server.log" 2>&1 & echo $! > "$PIDDIR/kafka.pid" )
  if wait_port 9092 90 "Kafka"; then
    local i
    for ((i=0; i<20; i++)); do
      "$KAFKA_MSYS/bin/kafka-broker-api-versions.sh" --bootstrap-server 127.0.0.1:9092 >/dev/null 2>&1 \
        && { echo "  [ok]   Kafka broker 探活通过（api-versions 可响应）"; return 0; }
      sleep 2
    done
    echo "  [warn] 端口已监听但 broker 探活未通过，查看 output/kafka_server.log"
    return 1
  fi
  return 1
}

start_mysqld_master() {
  if listening 3307; then echo "  [skip] MySQL 主库 3307 已在运行"; return 0; fi
  bg_start mysql-master "$MYSQLD" --no-defaults --datadir="$DATA/master" --basedir="$MYSQL_BASE" \
    --port=3307 --bind-address=127.0.0.1 --server-id=1 --gtid-mode=ON --enforce-gtid-consistency=ON \
    --log-bin=mysql-bin > "$ROOT_MSYS/output/mysql_master.log" 2>&1
  wait_mysql 3307 90 "MySQL 主库"
}

start_mysqld_slave() {
  if listening 3308; then echo "  [skip] MySQL 从库 3308 已在运行"; return 0; fi
  bg_start mysql-slave "$MYSQLD" --no-defaults --datadir="$DATA/slave" --basedir="$MYSQL_BASE" \
    --port=3308 --bind-address=127.0.0.1 --server-id=2 --gtid-mode=ON --enforce-gtid-consistency=ON \
    --read-only=ON --super-read-only=ON > "$ROOT_MSYS/output/mysql_slave.log" 2>&1
  wait_mysql 3308 90 "MySQL 从库"
}

ensure_kafka_topics() {
  # 消费并发 = 3（KafkaConsumerConfig.setConcurrency(3)）：分区数必须 >= 并发度，
  # 否则多出的 consumer 空转。qb-events 由 app 单体生产+消费，auto-create 默认 1 分区需纠偏。
  # 所有 kafka-topics.sh 调用都套 timeout：broker 僵死（如 DUPLICATE_BROKER_REGISTRATION）
  # 时 Java CLI 会无限挂起，不设护栏会拖垮整个证据流程。
  if ! listening 9092; then echo "  [skip] Kafka 未运行，跳过 topic 保障"; return 0; fi
  local partitions
  # 超时取 120/150s：本机 Kafka CLI 冷启动实测 ~73s，护栏的意义是"broker 僵死时不再无限重连"，
  # 而不是在正常冷启动路径上误杀（timeout 过小的反作用已实测踩过一次）。
  partitions="$(timeout 120 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 --describe --topic qb-events 2>/dev/null \
    | grep -oE 'PartitionCount: [0-9]+' | head -1 | grep -oE '[0-9]+')"
  if [ -z "$partitions" ]; then
    timeout 150 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 --create --if-not-exists \
      --topic qb-events --partitions 3 --replication-factor 1 >/dev/null 2>&1 \
      && echo "  [ok]   topic qb-events 已创建（3 分区）" \
      || echo "  [warn] qb-events 创建失败（消费并发 3 将空转），查看 output/kafka_server.log"
  elif [ "$partitions" -lt 3 ]; then
    timeout 150 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 --alter --topic qb-events \
      --partitions 3 >/dev/null 2>&1 \
      && echo "  [ok]   qb-events 分区 $partitions → 3（只增不减）" \
      || echo "  [warn] qb-events 扩分区失败（现有 $partitions，并发 3 会有 consumer 空转）"
  else
    echo "  [skip] qb-events 已有 $partitions 分区（>= 3）"
  fi
}

ensure_cloud_db_users() {
  # cloud 三服务专用最小权限用户（审计 Item 12：服务不再用 root 连库），每用户只限本库。
  # 幂等：CREATE USER IF NOT EXISTS + ALTER USER（已存在则重置为脚本密码，保持确定性）；
  # schema 预建：URL 已去掉 createDatabaseIfNotExist——应用账号不该有全局建库权限。
  if ! listening 3307; then echo "  [skip] MySQL 主库未运行，跳过 cloud 用户配置"; return 0; fi
  local sql="" db
  for db in qb_auth qb_bank qb_practice; do
    sql+="CREATE DATABASE IF NOT EXISTS \`$db\`; "
    sql+="CREATE USER IF NOT EXISTS '${db}'@'127.0.0.1' IDENTIFIED BY '${CLOUD_DB_PASSWORD}'; "
    sql+="ALTER USER '${db}'@'127.0.0.1' IDENTIFIED BY '${CLOUD_DB_PASSWORD}'; "
    sql+="GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${db}'@'127.0.0.1'; "
  done
  sql+="FLUSH PRIVILEGES;"
  if "$MYSQL_EXE" -uroot -p"$PASSWORD" -h127.0.0.1 -P3307 --protocol=tcp -e "$sql" >/dev/null 2>&1; then
    echo "  [ok]   cloud 专用用户就绪（qb_auth/qb_bank/qb_practice，各限本库）"
  else
    echo "  [warn] cloud 用户配置失败（cloud-evidence 首跑将连库失败），查看 output/mysql_master.log"
  fi
}

stop_pid() { # $1=pid 文件名 $2=端口 $3=名称  local p
  if [ -f "$PIDDIR/$1.pid" ]; then
    p="$(tr -d '\r\n ' < "$PIDDIR/$1.pid")"
    if [ -n "$p" ]; then kill "$p" >/dev/null 2>&1 && echo "  [ok]   $3 已停止（PID $p）" || echo "  [info] $3 PID $p 已不存在"; fi
    rm -f "$PIDDIR/$1.pid"
  fi
  # 兜底：按端口再杀一次（PID 文件可能过期）
  for p in $(netstat -ano 2>/dev/null | grep -E "[:.]$2[[:space:]]" | grep LISTENING | awk '{print $NF}' | sort -u); do
    kill "$p" >/dev/null 2>&1 && echo "  [ok]   $3 兜底停止（端口 $2，PID $p）"
  done
  listening "$2" || echo "  [ok]   $3 端口 $2 已释放"
}

status() {
  printf '%-24s %s\n' "组件" "状态"
  printf '%-24s %s\n' "------------------------" "--------------------"
  for pair in "3306:MySQL 业务库 3306" "3307:MySQL 主库 3307" "3308:MySQL 从库 3308" "6379:Redis 6379" "9092:Kafka 9092"; do
    local port="${pair%%:*}" label="${pair#*:}"
    if listening "$port"; then printf '%-24s %s\n' "$label" "运行中"
    else printf '%-24s %s\n' "$label" "未运行"; fi
  done
}

case "${1:-start}" in
  status) status; exit 0 ;;
  stop)
    echo "=== 停止证据环境（datadir 保留，可免初始化重启）==="
    stop_pid kafka 9092 "Kafka 9092"
    stop_pid mysql-slave 3308 "MySQL 从库 3308"
    stop_pid mysql-master 3307 "MySQL 主库 3307"
    stop_pid redis 6379 "Redis 6379"
    echo "完成。业务库 3306 未动。"
    exit 0 ;;
  start) ;;
  *) echo "用法：$0 [start|stop|status]"; exit 1 ;;
esac

echo "=== 证据环境自愈启动（ROOT=$ROOT_WIN）==="
echo "提示：组件随本终端会话存在；新会话重跑本脚本即可（幂等）。"
start_redis
start_kafka
start_mysqld_master
start_mysqld_slave

echo "=== 配置并校验主从复制（幂等）==="
if listening 3307 && listening 3308; then
  ./scripts/replication-setup.sh > "$ROOT_MSYS/output/replication_autostart.log" 2>&1
  grep -E "Replica_IO_Running|Replica_SQL_Running|复制收敛|slave_port|主从|错误|ERROR" \
    "$ROOT_MSYS/output/replication_autostart.log" | head -12
else
  echo "  [FAIL] 主从未全部就绪，跳过复制配置"
fi

echo "=== Kafka topic 与 cloud 专用 DB 用户（幂等）==="
ensure_kafka_topics
ensure_cloud_db_users

echo
status
echo
echo "下一步（全证据复跑）："
echo "  MYSQL_EVIDENCE=true REDIS_EVIDENCE=true KAFKA_EVIDENCE=true READ_REPLICA_EVIDENCE=true \\"
echo "    ./scripts/mvn.sh -B clean verify"
