#!/usr/bin/env bash
# BCrypt strength 三档对照实验 v2：login 吞吐随 cost 指数级衰减的实测。
#
# v1 的教训：BCrypt 的 cost 是自描述的——验证端 matches() 用的是存储哈希前缀里的 cost
# （如 $2a$10$ 的 10），与 PasswordEncoder 实例配置的 strength 无关。只改应用配置
# 不会影响登录耗时（v1 三档吞吐完全一致正是这个原因）。所以本实验改为：
# 每档先把 student 的 password_hash 换成对应 cost 生成的哈希，再压 login——
# 验证路径的 cost 由数据决定，这才是登录吞吐的真实控制变量。
#
# 产出：output/bcrypt_strength_<stamp>.log（三档吞吐/P95/P99 + 结论）
set -uo pipefail

cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/bcrypt_strength_${STAMP}.log"
exec > >(tee "$OUT") 2>&1

MYSQL="/c/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe"
export MYSQL_PWD="${MYSQL_PASSWORD:-783421}"
MYSQL_ARGS="-uroot -h127.0.0.1 -P3306 --protocol=tcp question_bank"
JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"
JSHELL="$JDK/bin/jshell.exe"
CRYPTO_JAR="C:/Users/Allen/.m2/repository/org/springframework/security/spring-security-crypto/6.4.5/spring-security-crypto-6.4.5.jar"
JCL_JAR="C:/Users/Allen/.m2/repository/org/springframework/spring-jcl/6.2.6/spring-jcl-6.2.6.jar"
BASE_URL="http://127.0.0.1:8080"
RPS_LOGIN="${RPS_LOGIN:-60}"
DURATION="${DURATION:-20}"

echo "=== BCrypt strength 对照实验 v2 $STAMP（开环 ${RPS_LOGIN} RPS，测 ${DURATION}s，哈希 cost 8/10/12）==="
"$MYSQL" $MYSQL_ARGS -e "SELECT VERSION() AS mysql_version;"

echo "=== 生成三档哈希（cost=8/10/12，同一明文 student123）==="
cat > target/tmp/genhash.jsh << 'EOF'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
System.out.println("HASH8=" + new BCryptPasswordEncoder(8).encode("student123"));
System.out.println("HASH10=" + new BCryptPasswordEncoder(10).encode("student123"));
System.out.println("HASH12=" + new BCryptPasswordEncoder(12).encode("student123"));
/exit
EOF
HASHES="$("$JSHELL" --class-path "$CRYPTO_JAR;$JCL_JAR" "C:/Project/QuestionBank/target/tmp/genhash.jsh" 2>&1 | grep -a "HASH")"
HASH8="$(echo "$HASHES" | grep -a '^HASH8=' | cut -d= -f2-)"
HASH10="$(echo "$HASHES" | grep -a '^HASH10=' | cut -d= -f2-)"
HASH12="$(echo "$HASHES" | grep -a '^HASH12=' | cut -d= -f2-)"
[ -z "$HASH8" ] || [ -z "$HASH10" ] || [ -z "$HASH12" ] && { echo "哈希生成失败"; exit 1; }
echo "HASH8  = $HASH8"
echo "HASH10 = $HASH10"
echo "HASH12 = $HASH12"

echo "=== 打包（一次，三档共用）==="
./scripts/mvn.sh -B -DskipTests package -q
APP_JAR="$(ls app/target/question-bank-m0-*.jar 2>/dev/null | grep -v '\.jar\.original' | head -1)"
[ -z "$APP_JAR" ] && { echo "打包失败：找不到可执行 jar"; exit 1; }
echo "jar: $APP_JAR"

ORIGINAL_HASH="$("$MYSQL" $MYSQL_ARGS -N -e "SELECT password_hash FROM user_account WHERE username='student'" | tr -d '\r')"
[ -z "$ORIGINAL_HASH" ] && { echo "未找到 student 账号，无法实验"; exit 1; }
echo "student 原哈希（实验后恢复）: $ORIGINAL_HASH"

restore_hash() {
  echo "=== 恢复 student 原哈希 ==="
  "$MYSQL" $MYSQL_ARGS -e "UPDATE user_account SET password_hash='$ORIGINAL_HASH' WHERE username='student'"
  "$MYSQL" $MYSQL_ARGS -N -e "SELECT password_hash FROM user_account WHERE username='student'" | tr -d '\r'
}
trap restore_hash EXIT

for STRENGTH in 8 10 12; do
  case $STRENGTH in
    8)  HASH="$HASH8" ;;
    10) HASH="$HASH10" ;;
    12) HASH="$HASH12" ;;
  esac
  echo
  echo "================ hash cost=$STRENGTH ================"
  "$MYSQL" $MYSQL_ARGS -e "UPDATE user_account SET password_hash='$HASH' WHERE username='student'"
  export DB_URL="jdbc:mysql://127.0.0.1:3306/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
  export DB_USERNAME=root
  export DB_PASSWORD="${MYSQL_PASSWORD:-783421}"
  "$JAVA" -Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
    -jar "$APP_JAR" --app.rate-limit.capacity=1000000 --app.rate-limit.window=PT1M \
    > "output/app_bcrypt${STRENGTH}_${STAMP}.log" 2>&1 &
  APP_PID=$!

  CODE=000
  for i in $(seq 1 60); do
    CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE_URL/actuator/health" 2>/dev/null)"
    [ "$CODE" = "200" ] && { echo "健康检查通过（第 $i 次）"; break; }
    sleep 2
  done
  if [ "$CODE" != "200" ]; then echo "应用启动失败（cost=$STRENGTH）"; kill "$APP_PID" 2>/dev/null; exit 1; fi

  "$JAVA" tools/loadgen/Loadgen.java --url "$BASE_URL/api/auth/login" --method POST \
    --body '{"username":"student","password":"student123"}' --rps "$RPS_LOGIN" --warmup 5 --duration "$DURATION" \
    --out "output/load_login_bcrypt${STRENGTH}_${STAMP}.json"

  echo "--- cost=$STRENGTH 结果摘要 ---"
  python - "$STRENGTH" "output/load_login_bcrypt${STRENGTH}_${STAMP}.json" << 'PYEOF'
import json, sys
strength, path = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    d = json.load(f)
print(f"cost={strength} | 实测吞吐={d['measuredRps']} RPS（目标 {d['targetRps']}） | "
      f"P50={d['p50Ms']}ms | P95={d['p95Ms']}ms | P99={d['p99Ms']}ms | 错误率={d['errorRatePct']}%")
PYEOF

  kill "$APP_PID" 2>/dev/null || taskkill //F //PID "$APP_PID" 2>/dev/null
  sleep 3
done

echo
echo "=== 恢复校验（下方应与原哈希一致）==="
