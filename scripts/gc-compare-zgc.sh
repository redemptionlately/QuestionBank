#!/usr/bin/env bash
# ZGC 对照实验：同一负载（published @100RPS 开环 30s）下对比 G1 与 ZGC(generational) 的停顿画像。
# 前提：G1 的基线来自 scripts/loadtest.sh + gc-report.sh；本脚本只产 ZGC 一侧，输出可比对的 GC 报告。
set -u
cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
GC_LOG="output/gc_zgc_${STAMP}.log"
JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"
BASE_URL="http://127.0.0.1:8080"
export DB_URL="jdbc:mysql://127.0.0.1:3306/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
export DB_USERNAME=root
export DB_PASSWORD="${MYSQL_PASSWORD:-783421}"

APP_JAR="$(ls app/target/question-bank-m0-*.jar 2>/dev/null | grep -v '\.jar\.original' | head -1)"
if [ -z "$APP_JAR" ]; then echo "先 ./scripts/mvn.sh -DskipTests package"; exit 1; fi

"$JAVA" -Xms512m -Xmx1g -XX:+UseZGC -XX:+ZGenerational \
  -Xlog:gc*,safepoint:file="$GC_LOG":time,uptime,level,tags \
  -jar "$APP_JAR" --app.rate-limit.capacity=1000000 --app.rate-limit.window=PT1M \
  > "output/app_zgc_${STAMP}.log" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null' EXIT

for i in $(seq 1 60); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE_URL/actuator/health" 2>/dev/null)"
  [ "$CODE" = "200" ] && { echo "健康检查通过（第 $i 次）"; break; }
  sleep 2
done
[ "$CODE" = "200" ] || { echo "启动失败"; exit 1; }

curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"student","password":"student123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4 > app/target/token.txt

"$JAVA" tools/loadgen/Loadgen.java --url "$BASE_URL/api/papers/published" --method GET \
  --token-file app/target/token.txt --rps 100 --warmup 10 --duration 30 \
  --out "output/load_published_zgc_${STAMP}.json"

sleep 2
echo "=== ZGC GC 报告 ==="
./scripts/gc-report.sh "$GC_LOG"
echo "GC 日志: $GC_LOG"
echo "延迟 JSON: output/load_published_zgc_${STAMP}.json"
