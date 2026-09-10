#!/usr/bin/env bash
# 多实例水平扩容真机证据（本机无 Docker，直接起两个 jar 实例共享同一 MySQL + Redis）。
#
# 回答三个"简历上敢不敢写"的问题：
#   1. 无状态：A 实例签发的 JWT，B 实例认不认（无 session 亲和能否扩容）
#   2. 共享限流：配额是全局的还是按实例翻倍（backend=redis vs local 对照实验）
#   3. 跨实例幂等：同一 Idempotency-Key 打到不同实例，会不会重复计分
#
# 用法：./scripts/multi-instance-evidence.sh
# 产物：output/multi_instance_<时间戳>.log
set -uo pipefail
cd /c/Project/QuestionBank

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/multi_instance_${STAMP}.log"
exec > >(tee "$OUT") 2>&1

JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"

# 必须绕开 HTTP 代理：本环境给 curl 配了代理，访问 127.0.0.1 会被代理挡回
# "502 upstream connect failed"（目标计算机积极拒绝），表现为实例明明没起来却探到 502。
export NO_PROXY="127.0.0.1,localhost"
export no_proxy="$NO_PROXY"
ROOT="$(pwd)"
if [ -f scripts/.env.local ]; then set -a; source scripts/.env.local; set +a; fi

PORT_A=8081
PORT_B=8082
# capacity 取小、窗口取长：让令牌桶的续杯速率（capacity/window）低到在一次探针内可忽略，
# 否则"放行 7"这种数字是 refill 造成的，会掩盖"共享 vs 各算各的"的真差异。
CAPACITY=${CAPACITY:-3}
# 窗口取 10 分钟：续杯速率 capacity/window 低到一次探针内几乎不产生新令牌。
# 实测 PT15S/PT60S 下共享桶都被 refill 抬高到放行 5，与本地的 6 拉不开差距，判据失效。
WINDOW=${WINDOW:-PT10M}
WINDOW_SECONDS=600

listening() { netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q LISTENING; }

REDIS_CLI='C:/Users/Allen/tools/redis/Redis-8.10.1-Windows-x64-msys2/redis-cli.exe'
# 清空限流计数（qb:ratelimit:*）。比"等窗口自然过期"可靠得多，也让脚本可反复复跑。
# 只删限流 key，不动 qb:token:*——否则正在用的登录 token 会被一起清掉。
reset_ratelimit() {
  if [ -f "$REDIS_CLI" ]; then
    "$REDIS_CLI" -p 6379 --scan --pattern 'qb:ratelimit:*' 2>/dev/null | while read -r k; do
      [ -n "$k" ] && "$REDIS_CLI" -p 6379 DEL "$k" >/dev/null 2>&1
    done
    echo "已清空限流 key（qb:ratelimit:*）"
  else
    echo "未找到 redis-cli（$REDIS_CLI），退化为等待窗口过期"
    sleep "$((WINDOW_SECONDS + 2))"
  fi
}

echo "=== 多实例水平扩容证据 $STAMP ==="
echo "容量/窗口: capacity=$CAPACITY window=$WINDOW"

# ---------- 1. 依赖环境（幂等） ----------
echo
echo "########## 1. 环境供给（MySQL 3306 / Redis 6379） ##########"
# 本证据只需要 MySQL 3306 + Redis，不需要 Kafka / 主从复制。
# start-evidence-env.sh 会顺带拉 Kafka，其 topic ensure 步骤在本机有 Java CLI 冷启动
# 过长甚至挂死的隐患 → 默认 SKIP_ENV=1 跳过整体供给，只校验必需端口；
# 确需完整环境时 SKIP_ENV=0 ./scripts/multi-instance-evidence.sh，并给 7 分钟上限。
if [ "${SKIP_ENV:-1}" = "1" ]; then
  echo "SKIP_ENV=1 → 跳过环境供给，直接校验端口"
else
  timeout 420 ./scripts/start-evidence-env.sh start 2>&1 | tail -15
fi
listening 3306 && echo "MySQL 3306: LISTENING" || { echo "MySQL 3306 未就绪，终止（可尝试 SKIP_ENV=0 重跑）"; exit 1; }
listening 6379 && echo "Redis 6379: LISTENING" || { echo "Redis 6379 未就绪，终止（可尝试 SKIP_ENV=0 重跑）"; exit 1; }

# ---------- 2. 打包 ----------
echo
echo "########## 2. 打包 jar ##########"
./scripts/mvn.sh -B -DskipTests package -q 2>&1 | tail -5
JAR="$ROOT/app/target/question-bank-m0-0.1.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "打包失败：$JAR 不存在"; exit 1; }
# jar 拷贝到 output/ 而不是 deploy/：deploy/question-bank-app.jar 可能正被长驻主进程运行，
# 运行中 jar 被同路径覆盖时 Windows 上旧句柄按缓存 zip 索引读新文件 → 类数据错位，
# 懒加载类（如 logback ThrowableProxy）加载失败且错误日志崩溃（2026-09-10 事故，已沉淀教训）
mkdir -p "$ROOT/output/jars"
JAR_WIN="$(cygpath -w "$ROOT/output/jars/multi-instance.jar")"
cp -f "$JAR" "$ROOT/output/jars/multi-instance.jar"
echo "jar 就绪: $JAR_WIN"

# ---------- 工具函数 ----------
start_instance() { # $1=端口 $2=限流后端 $3=容量（可选，默认 $CAPACITY）
  "$JAVA" -jar "$JAR_WIN" \
    --server.port="$1" \
    --app.rate-limit.backend="$2" \
    --app.rate-limit.capacity="${3:-$CAPACITY}" \
    --app.rate-limit.window="$WINDOW" \
    --app.token-store="${TOKEN_STORE:-redis}" \
    > "output/instance_$1_${STAMP}.log" 2>&1 &
  echo $!
}

wait_ready() { # $1=端口 $2=最长秒
  for _ in $(seq 1 "$2"); do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
      "http://127.0.0.1:$1/api/auth/login" \
      -H "Content-Type: application/json" -d '{"username":"__probe__","password":"__probe__"}')
    # 只认应用自己产出的状态码。000=连不上，502/503=被代理或网关挡回，都不算就绪
    case "$code" in
      200|400|401|403|404|500) echo "实例 :$1 就绪（探针 HTTP $code）"; return 0 ;;
    esac
    sleep 2
  done
  echo "实例 :$1 启动超时（最后探针码 ${code:-无响应}）"; return 1
}

stop_all() {
  for p in "$PORT_A" "$PORT_B"; do
    pid=$(netstat -ano 2>/dev/null | grep -E "[:.]$p[[:space:]].*LISTENING" | awk '{print $NF}' | head -1)
    if [ -n "$pid" ] && [ "$pid" != "0" ]; then
      taskkill /F /PID "$pid" >/dev/null 2>&1
      echo "已停止 :$p（PID $pid）"
    fi
  done
  sleep 2
}

login() { # $1=端口 $2=用户名
  curl -s -X POST "http://127.0.0.1:$1/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$2\",\"password\":\"$3\"}"
}

# ---------- 3. 证据一：无状态鉴权 ----------
echo
echo "########## 3. 证据一：无状态（A 签发 token，B 是否认） ##########"
stop_all
BACKEND=${BACKEND:-redis}
echo "启动 A:$PORT_A B:$PORT_B（rate-limit.backend=$BACKEND, token-store=${TOKEN_STORE:-redis}）"
echo "（把 TOKEN_STORE=local 再跑一次即可复现『B 不认 A 签发的 token』——本机内存 token 的扩容阻塞缺陷）"
start_instance "$PORT_A" "$BACKEND" >/dev/null
start_instance "$PORT_B" "$BACKEND" >/dev/null
wait_ready "$PORT_A" 90 || exit 1
wait_ready "$PORT_B" 90 || exit 1

LOGIN_A=$(login "$PORT_A" admin admin123)
TOKEN=$(printf '%s' "$LOGIN_A" | grep -aoE '"token":"[^"]+"' | head -1 | cut -d'"' -f4)
echo "A(:8081) 登录返回: $(printf '%s' "$LOGIN_A" | head -c 120)"
if [ -z "$TOKEN" ]; then echo "登录失败，终止"; stop_all; exit 1; fi

CODE_B=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT_B/api/papers/published" -H "Authorization: Bearer $TOKEN")
CODE_BARE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT_B/api/papers/published")
CODE_A=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT_A/api/papers/published" -H "Authorization: Bearer $TOKEN")
echo "B(:8082) 持 A 签发的 token 访问受保护接口 → HTTP $CODE_B"
echo "B(:8082) 不带 token 访问同一接口       → HTTP $CODE_BARE"
echo "A(:8081) 持自己的 token 访问           → HTTP $CODE_A"

# ---------- 4. 证据二/三：限流共享性对照实验 ----------
# 关键设计：限流 key = remoteAddr + requestURI。两个实例看到的 remoteAddr 都是 127.0.0.1，
# 打的是同一个 URI → 如果是共享后端，两实例应该共用同一个配额；否则各算各的。
REDIS_OK=""
LOCAL_OK=""

# 注意：不要拿"放行 ≤ capacity"当共享判据——令牌桶会续杯（且 RedisTokenBucketRateLimiter 的
# Math.max(1, refillPerMilli) 下限把续杯抬到至少 1 令牌/秒，长窗口配置会被它架空，见证据输出）。
# 真正的判据是两轮对照：同样 8 个请求，本地计数的放行应显著多于共享计数。
# 用 curl --parallel 并发突发：把探针耗时压到亚秒级，让续杯贡献趋近于 0（顺序发 8 个要好几秒，
# 实测续杯能白送 2-3 个令牌，把两轮拉平）。
probe_limit() { # $1=标签 $2=总请求数
  local label="$1" total="$2" i port ok limited other codes
  local args=()
  for i in $(seq 1 "$total"); do
    if [ $((i % 2)) -eq 0 ]; then port=$PORT_A; else port=$PORT_B; fi
    args+=("--url" "http://127.0.0.1:$port/api/papers/published" "-H" "Authorization: Bearer $TOKEN" "-o" "/dev/null" "-w" "%{http_code}\n")
  done
  codes=$(curl -s --parallel --parallel-immediate "${args[@]}")
  ok=$(printf '%s\n' "$codes" | grep -c '^200$')
  limited=$(printf '%s\n' "$codes" | grep -c '^429$')
  other=$(printf '%s\n' "$codes" | grep -cvE '^(200|429)$')
  echo "[$label] 并发突发 $total（A/B 各半）→ 放行 $ok / 429 $limited / 其他 $other"
  case "$label" in
    *redis*) REDIS_OK=$ok ;;
    *local*) LOCAL_OK=$ok ;;
  esac
}

echo
echo "########## 4. 限流共享性对照实验（capacity=$CAPACITY，交替打 A/B） ##########"
reset_ratelimit   # 清掉历史计数，保证每轮都从空桶开始
probe_limit "backend=$BACKEND" 8

if [ "$BACKEND" = "redis" ]; then
  echo
  echo "--- 回归探针：配额耗尽后 1.5s 不应白送令牌 ---"
  echo "（续杯下限缺陷 Math.max(1, refillPerMilli) 会在此 1.5s 攒出 1 个令牌放行 200；修复后必须仍 429）"
  sleep 1.5
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT_A/api/papers/published" -H "Authorization: Bearer $TOKEN")
  if [ "$CODE" = "429" ]; then
    echo "长窗口续杯下限回归：1.5s 后仍 429 = 通过"
  else
    echo "长窗口续杯下限回归：1.5s 后 HTTP $CODE（应为 429）= 失败，续杯下限缺陷复现"
  fi
fi

if [ "$BACKEND" = "redis" ]; then
  echo
  echo "--- 对照组：切回 local（JVM 内存）后端重跑（token 存储仍为 redis，只换限流后端）---"
  stop_all
  start_instance "$PORT_A" local >/dev/null
  start_instance "$PORT_B" local >/dev/null
  wait_ready "$PORT_A" 90 || exit 1
  wait_ready "$PORT_B" 90 || exit 1
  reset_ratelimit
  probe_limit "backend=local" 8

  echo
  echo "--- 对照结论（capacity=$CAPACITY，8 个请求 A/B 各 4 个）---"
  echo "共享后端（redis）放行 $REDIS_OK 次 / 本地后端（JVM 内存）放行 $LOCAL_OK 次"
  echo "背景：历史上 Math.max(1, refillPerMilli) 下限曾把续杯抬到 >=1 令牌/秒、长窗口被架空（已修复，见上方回归探针）；"
  echo "      现共享桶放行数 = capacity + 探针耗时内的微量续杯。"
  if [ -n "$REDIS_OK" ] && [ "$LOCAL_OK" -gt "$REDIS_OK" ] && [ "$LOCAL_OK" -ge $((CAPACITY * 2 - 1)) ]; then
    echo "判定：本地后端放行 $LOCAL_OK ≈ 2×capacity（每实例各一个桶），显著多于共享后端的 $REDIS_OK"
    echo "      → 多实例下内存限流的配额按实例翻倍、全局配额失效；水平扩容必须 app.rate-limit.backend=redis"
  else
    echo "判定：两轮差异未达预期，需复核"
  fi
fi

# ---------- 5. 证据四：跨实例幂等 ----------
echo
echo "########## 5. 证据四：跨实例幂等（同一 Idempotency-Key 打到不同实例） ##########"
# 上一步的限流探针已经把 /api/papers/published 的配额打满，这里必须换新实例、放开容量，
# 否则后面的业务调用会被 429 挡住（首轮就踩了一次，表现是拿不到试卷 ID 却误报"DB 无数据"）。
echo "重启实例并放开限流容量，避免上一步配额残留污染业务调用…"
stop_all
start_instance "$PORT_A" "$BACKEND" 99999 >/dev/null
start_instance "$PORT_B" "$BACKEND" 99999 >/dev/null
wait_ready "$PORT_A" 90 || exit 1
wait_ready "$PORT_B" 90 || exit 1
PUBLISHED=$(curl -s "http://127.0.0.1:$PORT_A/api/papers/published" -H "Authorization: Bearer $TOKEN")
echo "已发布试卷原始响应: $(printf '%s' "$PUBLISHED" | head -c 300)"
PAPER=$(printf '%s' "$PUBLISHED" | grep -aoE '"paperVersionId":[0-9]+' | head -1 | grep -oE '[0-9]+')
[ -z "$PAPER" ] && PAPER=$(printf '%s' "$PUBLISHED" | grep -aoE '"id":[0-9]+' | head -1 | grep -oE '[0-9]+')
if [ -z "$PAPER" ]; then
  echo "未取到已发布试卷 ID（DB 无已发布数据）→ 本项标为『待补』，不伪造"
else
  echo "使用 paperVersionId=$PAPER"
  # 练习是学生侧操作：admin 调 /api/practices 会被 FORBIDDEN 拒绝（首轮实测踩到），必须用 student 账号
  STUDENT_JSON=$(login "$PORT_A" student student123)
  STOKEN=$(printf '%s' "$STUDENT_JSON" | grep -aoE '"token":"[^"]+"' | head -1 | cut -d'"' -f4)
  echo "student 在 A 登录: $(printf '%s' "$STUDENT_JSON" | head -c 120)"
  if [ -z "$STOKEN" ]; then
    echo "student 登录失败 → 幂等项标为『待补』，不伪造"
  else
  CREATE_JSON=$(curl -s -X POST "http://127.0.0.1:$PORT_A/api/practices" \
    -H "Authorization: Bearer $STOKEN" -H "Content-Type: application/json" \
    -d "{\"paperVersionId\":$PAPER}")
  echo "A 创建练习响应: $(printf '%s' "$CREATE_JSON" | head -c 200)"
  SID=$(printf '%s' "$CREATE_JSON" | grep -aoE '"id":[0-9]+' | head -1 | grep -oE '[0-9]+')
  echo "A 创建练习会话 id=$SID"
  KEY="mi-$(date +%s)"
  R1=$(curl -s -X POST "http://127.0.0.1:$PORT_A/api/practices/$SID/submit" -H "Authorization: Bearer $STOKEN" -H "Idempotency-Key: $KEY")
  R2=$(curl -s -X POST "http://127.0.0.1:$PORT_B/api/practices/$SID/submit" -H "Authorization: Bearer $STOKEN" -H "Idempotency-Key: $KEY")
  echo "A 实例提交: $(printf '%s' "$R1" | head -c 160)"
  echo "B 实例提交: $(printf '%s' "$R2" | head -c 160)"
  [ "$R1" = "$R2" ] && echo "判定：跨实例幂等成立（两次提交返回完全一致）" || echo "判定：两次返回不一致，需排查"
  fi
fi

# ---------- 收尾 ----------
echo
echo "########## 收尾 ##########"
stop_all
echo "证据日志: $OUT"
echo "实例日志: output/instance_${PORT_A}_${STAMP}.log / output/instance_${PORT_B}_${STAMP}.log"
echo "=== 多实例证据结束 $STAMP ==="
