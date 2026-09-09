#!/usr/bin/env bash
# 审计第三阶段（中优先级 20 项）补充取证：三项此前只能靠"代码存在"证明的结论，这里给出运行时证据。
#
#   Item 3  —— Kafka 消费并发 3 的前提成立：qb-events 分区数 >= 3（并发度 <= 分区数才有意义）
#   Item 12 —— cloud 三服务不再用 root 连库：专用用户存在、GRANT 只限本库、三 schema 各自独立
#   Item 18 —— cloud auth 请求体校验：空字段 400 + VALIDATION_ERROR 契约，合法登录仍 200
#
# 用法（幂等，可反复重跑）：
#   ./scripts/phase3-audit-evidence.sh                       # 常规取证（已运行环境直接跳过启动）
#   KAFKA_FRESH=true ./scripts/phase3-audit-evidence.sh      # KRaft 元数据脏了先重建数据目录再取证
#
# 生命周期边界同 scripts/start-evidence-env.sh：组件随当前终端会话存在，
# 因此本脚本把"拉起环境 + 取证 + 收尾"放在同一个会话内完成。
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="C:/Project/QuestionBank"
OUT="$ROOT/output"
PIDS="$ROOT/.workbuddy/pids"
JAVA="/c/Program Files/Java/jdk-21.0.12/bin/java"
MYSQL_EXE='C:/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe'
MYSQL_PASSWORD='783421'
CLOUD_DB_PASSWORD="${CLOUD_DB_PASSWORD:-qb-cloud-demo-pw}"
KAFKA_MSYS='/c/Users/Allen/tools/kafka/kafka_2.13-4.0.0'
mkdir -p "$OUT" "$PIDS"

log() { echo "[$(date +%H:%M:%S)] $*"; }
ev()  { echo "[evidence] $*"; }
fatal() { log "FAIL: $*"; exit 1; }

listening() { netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q LISTENING; }
mysql_q() { "$MYSQL_EXE" -uroot -p"$MYSQL_PASSWORD" -h127.0.0.1 -P3307 --protocol=tcp -N -B -e "$1" 2>/dev/null; }

start_jar() { # start_jar <name> <jar> <xmx> <port>
  local name="$1" jar="$2" xmx="$3" port="$4"
  local pidfile="$PIDS/phase3_$name.pid"
  if listening "$port"; then log "$name 端口 $port 已在监听，跳过启动"; return 0; fi
  (cd "$ROOT" && "$JAVA" -Xms128m -Xmx"$xmx" -XX:MaxMetaspaceSize=256m \
      -jar "$jar" > "$OUT/phase3_$name.log" 2>&1 & echo $! > "$pidfile")
  log "$name 启动中 (pid $(cat "$pidfile"), port $port)"
}

stop_jar() { # stop_jar <name> <port>
  # 逐个声明：同一行 local 里引用同语句变量（pidfile 用到 name）在 set -u 下会拿不到值
  local name="$1"
  local port="$2"
  local pidfile="$PIDS/phase3_$name.pid"
  local pid="" i wpid
  [ -f "$pidfile" ] && { pid=$(cat "$pidfile"); kill "$pid" 2>/dev/null; rm -f "$pidfile"; }
  for i in $(seq 1 20); do ! listening "$port" && break; sleep 0.5; done
  if listening "$port"; then
    wpid=$(netstat -ano 2>/dev/null | grep -i listening | grep ":$port " | awk '{print $NF}' | head -1)
    [ -n "${wpid:-}" ] && { log "$name 强杀残留 (winpid=$wpid)"; taskkill /F /PID "$wpid" >/dev/null 2>&1; }
    for i in $(seq 1 10); do ! listening "$port" && break; sleep 0.5; done
  fi
  listening "$port" && log "WARN: $name 端口 $port 未释放" || log "$name 已停止（端口 $port 已释放）"
}

wait_http() { # wait_http <url> <timeout_seconds> <label>
  local i code
  for ((i=0; i<$2; i++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$1" 2>/dev/null)
    [ "$code" = "200" ] && { log "$3 就绪（${i}s）"; return 0; }; sleep 1
  done
  fatal "$3 超时未就绪（$2s，url=$1）"
}

# ---------------------------------------------------------------- Item 3
log "===== Item 3：Kafka 消费并发 3 的前提（分区数 >= 并发度）====="
KAFKA_FRESH="${KAFKA_FRESH:-false}" ./scripts/start-evidence-env.sh start || fatal "证据环境拉起失败"
listening 9092 || fatal "Kafka 未运行"

# 静态前提：app 消费容器的并发度
CONCURRENCY=$(grep -rhoE "setConcurrency\([0-9]+\)" app/src/main/java/com/allen/questionbank/event/KafkaConsumerConfig.java | grep -oE '[0-9]+')
ev "消费者并发度（KafkaConsumerConfig.setConcurrency）= $CONCURRENCY"

PARTITIONS=""
for i in $(seq 1 4); do
  PARTITIONS=$(timeout 120 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 \
                 --describe --topic qb-events 2>/dev/null \
               | grep -oE 'PartitionCount: [0-9]+' | head -1 | grep -oE '[0-9]+')
  [ -n "$PARTITIONS" ] && break
  # 缺失则由 IMF 补建（与 start-evidence-env.sh 的 ensure_kafka_topics 同一约定）；
  # 超时留 150s：本机 Kafka CLI 的 JVM 冷启动实测约 73s。
  timeout 150 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 --create --if-not-exists \
    --topic qb-events --partitions 3 --replication-factor 1 >/dev/null 2>&1
  sleep 2
done
[ -n "$PARTITIONS" ] || fatal "qb-events 未能创建/描述成功，查看 output/kafka_server.log"
ev "qb-events 分区数 = $PARTITIONS（要求 >= $CONCURRENCY）"
[ "$PARTITIONS" -ge "$CONCURRENCY" ] || fatal "分区数 $PARTITIONS < 并发度 $CONCURRENCY，多出的 consumer 会空转"

ev "  分区明细（前 3 个 partition 的 leader 分布）："
timeout 120 "$KAFKA_MSYS/bin/kafka-topics.sh" --bootstrap-server 127.0.0.1:9092 \
  --describe --topic qb-events 2>/dev/null | grep -E "Partition: " | head -3 | sed 's/^/[evidence]   /'

# ---------------------------------------------------------------- Item 12
log "===== Item 12：cloud 专用最小权限用户（不再是 root 连库）====="
listening 3307 || fatal "MySQL 主库未运行"
USERS=$(mysql_q "SELECT user,host FROM mysql.user WHERE user LIKE 'qb_%' ORDER BY user;")
[ -n "$USERS" ] || fatal "未查到 qb_* 专用用户"
ev "专用用户清单："
echo "$USERS" | sed 's/^/[evidence]   /'

for db in qb_auth qb_bank qb_practice; do
  GRANTS=$(mysql_q "SHOW GRANTS FOR '$db'@'127.0.0.1';" | grep -oE 'ON `[^`]+`\.\*' | tr -d '`')
  ev "$db 的 GRANT 作用范围 = $(echo "$GRANTS" | tr '\n' ' ')"
  echo "$GRANTS" | grep -qvE "^ON $db\.\*$" && fatal "$db 用户的权限越过了本库（GRANTS=$GRANTS）"
done
ev "  判定：三个用户的 GRANT 均只作用于各自本库，无跨库/全局权限"

ROOT_USER_COUNT=$(grep -rl "username: root" cloud/*/src/main/resources/application.yml 2>/dev/null | wc -l)
ev "cloud 配置中仍用 root 连库的服务数 = $ROOT_USER_COUNT（要求 0）"
[ "$ROOT_USER_COUNT" -eq 0 ] || fatal "仍有 cloud 服务使用 root 连库"

LOGIN_AS_QB_AUTH=$("$MYSQL_EXE" -uqb_auth -p"$CLOUD_DB_PASSWORD" -h127.0.0.1 -P3307 --protocol=tcp \
                     -N -B -e "SELECT CURRENT_USER();" 2>/dev/null)
[ -n "$LOGIN_AS_QB_AUTH" ] || fatal "qb_auth 专用用户无法登录（用户或密码不匹配）"
ev "用 qb_auth 专用账号直连成功：CURRENT_USER() = $LOGIN_AS_QB_AUTH"

# ---------------------------------------------------------------- Item 18
log "===== Item 18：cloud auth 请求体参数校验（@Valid + 统一错误契约）====="
EUREKA_JAR=$(ls cloud/discovery-service/target/*.jar 2>/dev/null | grep -v original | head -1)
AUTH_JAR=$(ls cloud/auth-service/target/*.jar 2>/dev/null | grep -v original | head -1)
[ -n "$EUREKA_JAR" ] || fatal "未找到 discovery jar，先跑 ./scripts/mvn.sh -B -f cloud/pom.xml package -DskipTests"
[ -n "$AUTH_JAR" ] || fatal "未找到 auth jar，先跑 ./scripts/mvn.sh -B -f cloud/pom.xml package -DskipTests"

start_jar discovery "$EUREKA_JAR" 256m 8761
wait_http "http://127.0.0.1:8761/" 90 "discovery"
start_jar auth "$AUTH_JAR" 256m 8081
wait_http "http://127.0.0.1:8081/actuator/health" 90 "auth"

BODY=$(curl -s -X POST http://127.0.0.1:8081/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"","password":""}' -w '\n%{http_code}')
INVALID_CODE=$(echo "$BODY" | tail -1)
INVALID_BODY=$(echo "$BODY" | head -1)
ev "空 username/password -> HTTP $INVALID_CODE"
ev "错误体 = $INVALID_BODY"
[ "$INVALID_CODE" = "400" ] || fatal "空字段未被 @Valid 拦截（期望 400，实际 $INVALID_CODE）"
echo "$INVALID_BODY" | grep -q '"code":"VALIDATION_ERROR"' \
  || fatal "错误体未使用统一契约（期望 code=VALIDATION_ERROR）"
echo "$INVALID_BODY" | grep -qE '"requestId"' \
  || fatal "错误体缺 requestId（无法串网关日志）"

OK_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8081/api/auth/login \
            -H 'Content-Type: application/json' -d '{"username":"teacher","password":"teacher123"}')
ev "合法登录 -> HTTP $OK_CODE（期望 200，证明 @Valid 没有误伤正常请求）"
[ "$OK_CODE" = "200" ] || fatal "合法登录被拒：$OK_CODE（校验规则过严或播种数据不可用）"

# ---------------------------------------------------------------- 收尾
log "===== 收尾：停止本次取证拉起的服务 ====="
stop_jar auth 8081
stop_jar discovery 8761
log "三项证据全部通过。原始日志：$OUT/phase3_*.log"
