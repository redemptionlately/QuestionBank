#!/usr/bin/env bash
# Prometheus 指标端点证据：启动应用 -> 制造流量 -> 抓取 /actuator/prometheus -> 抽取 RED/JVM/连接池关键指标。
set -u
cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/prometheus_evidence_${STAMP}.log"
JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"
BASE_URL="http://127.0.0.1:8080"
export DB_URL="jdbc:mysql://127.0.0.1:3306/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
export DB_USERNAME=root
export DB_PASSWORD="${MYSQL_PASSWORD:-783421}"

APP_JAR="$(ls app/target/question-bank-m0-*.jar 2>/dev/null | grep -v '\.jar\.original' | head -1)"
[ -z "$APP_JAR" ] && { echo "先 ./scripts/mvn.sh -DskipTests package"; exit 1; }

"$JAVA" -Xms512m -Xmx1g -jar "$APP_JAR" --app.rate-limit.capacity=1000000 --app.rate-limit.window=PT1M \
  > "output/app_prom_${STAMP}.log" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null' EXIT

for i in $(seq 1 60); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE_URL/actuator/health" 2>/dev/null)"
  [ "$CODE" = "200" ] && break; sleep 2
done

# 制造 RED 三类样本：Rate（多次成功请求）+ Error（401 未认证）+ Duration（慢登录 BCrypt）
TOKEN="$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"student","password":"student123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)"
for i in $(seq 1 20); do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/papers/published"; done
curl -s -o /dev/null "$BASE_URL/api/practices"   # 未带 token → 401（错误路径样本）

echo "=== /actuator/prometheus 抓取样本（完整前 5 行 + 关键指标）==="
curl -s "$BASE_URL/actuator/prometheus" > "$OUT"
head -5 "$OUT"
echo "..."
echo "--- HTTP RED 指标 ---"
grep -a '^http_server_requests_seconds_count' "$OUT"
grep -a '^http_server_requests_seconds_sum' "$OUT" | head -2
echo "--- JVM 内存 ---"
grep -a '^jvm_memory_used_bytes{area="heap"' "$OUT"
echo "--- HikariCP 连接池 ---"
grep -aE '^hikaricp_connections(_active|_idle|_max)?\{' "$OUT"
echo
echo "完整抓取已保存: $OUT ($(grep -ac '' "$OUT") 行)"
