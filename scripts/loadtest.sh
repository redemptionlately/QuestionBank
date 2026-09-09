#!/usr/bin/env bash
# 开环压测 + JFR 录制，产出原始证据到 output/。
# 说明：启动时把限流容量调到极大，否则测到的是限流器而不是业务容量。
set -uo pipefail

cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/load_${STAMP}.log"
JFR_FILE="output/load_${STAMP}.jfr"
exec > >(tee "$OUT") 2>&1

MYSQL="/c/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe"
export MYSQL_PWD="${MYSQL_PASSWORD:-783421}"
MYSQL_ARGS="-uroot -h127.0.0.1 -P3306 --protocol=tcp question_bank"
JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"
JCMD="$JDK/bin/jcmd.exe"
BASE_URL="http://127.0.0.1:8080"

RPS_PUBLISHED="${RPS_PUBLISHED:-100}"
RPS_LOGIN="${RPS_LOGIN:-20}"

echo "=== 压测开始 $STAMP ==="
echo "JDK: $("$JAVA" -version 2>&1 | head -1)"
"$MYSQL" $MYSQL_ARGS -e "SELECT VERSION() AS mysql_version, @@transaction_isolation AS isolation;"

echo "=== 1/6 打包 ==="
./scripts/mvn.sh -B -DskipTests package -q
APP_JAR="$(ls app/target/question-bank-m0-*.jar 2>/dev/null | grep -v '\.jar\.original' | head -1)"
if [ -z "$APP_JAR" ]; then echo "打包失败：找不到可执行 jar"; exit 1; fi
echo "jar: $APP_JAR"

echo "=== 2/6 灌入种子数据 ==="
"$MYSQL" $MYSQL_ARGS < scripts/sql/seed_papers.sql

echo "=== 3/6 启动应用 ==="
export DB_URL="jdbc:mysql://127.0.0.1:3306/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
export DB_USERNAME=root
export DB_PASSWORD="${MYSQL_PASSWORD:-783421}"
"$JAVA" -Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Xlog:gc*,safepoint:file="output/gc_${STAMP}.log":time,uptime,level,tags \
  -XX:StartFlightRecording=filename="$JFR_FILE",settings=profile,duration=150s \
  -jar "$APP_JAR" --app.rate-limit.capacity=1000000 --app.rate-limit.window=PT1M \
  > "output/app_${STAMP}.log" 2>&1 &
APP_PID=$!
echo "应用 PID=$APP_PID（GC 日志: output/gc_${STAMP}.log）"

cleanup() {
  echo "=== 6/6 停止应用 ==="
  kill "$APP_PID" 2>/dev/null || taskkill //F //PID "$APP_PID" 2>/dev/null
}
trap cleanup EXIT

for i in $(seq 1 60); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE_URL/actuator/health" 2>/dev/null)"
  if [ "$CODE" = "200" ]; then echo "健康检查通过（第 $i 次）"; break; fi
  sleep 2
done
if [ "$CODE" != "200" ]; then echo "应用启动失败，日志见 output/app_${STAMP}.log"; exit 1; fi

echo "=== 4/6 获取 token ==="
TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"student","password":"student123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)"
if [ -z "$TOKEN" ]; then echo "登录失败"; exit 1; fi
echo "$TOKEN" > app/target/token.txt
echo "token 已保存"

echo "=== JFR 录制已在 JVM 启动参数中开启（jcmd attach 在本机会被拒绝访问，改用 StartFlightRecording）==="

echo "=== 5/6 场景 A：GET /api/papers/published（读路径，开环 ${RPS_PUBLISHED} RPS）==="
"$JAVA" tools/loadgen/Loadgen.java --url "$BASE_URL/api/papers/published" --method GET \
  --token-file app/target/token.txt --rps "$RPS_PUBLISHED" --warmup 10 --duration 30 \
  --out "output/load_published_${STAMP}.json"

echo
echo "=== 5/6 场景 B：POST /api/auth/login（BCrypt 密集，开环 ${RPS_LOGIN} RPS）==="
"$JAVA" tools/loadgen/Loadgen.java --url "$BASE_URL/api/auth/login" --method POST \
  --body '{"username":"student","password":"student123"}' --rps "$RPS_LOGIN" --warmup 5 --duration 20 \
  --out "output/load_login_${STAMP}.json"

echo
echo "=== HikariCP / Tomcat 池指标（actuator 需要认证，带 token 访问）==="
curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/actuator/metrics/hikaricp.connections" 2>/dev/null | head -c 400
echo
curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/actuator/metrics/tomcat.threads.busy" 2>/dev/null | head -c 400
echo
curl -s -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/metrics" 2>/dev/null | head -c 400
echo

echo "=== 等待 JFR 落盘 ==="
for i in $(seq 1 60); do
  if [ -s "$JFR_FILE" ]; then
    echo "JFR 已生成: $JFR_FILE ($(stat -c%s "$JFR_FILE") bytes)"
    break
  fi
  sleep 2
done

echo "证据日志: $OUT"
echo "JFR 文件: $JFR_FILE (可用 JDK Mission Control 或 jfr summary 查看)"
