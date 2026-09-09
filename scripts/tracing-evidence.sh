#!/usr/bin/env bash
# =====================================================================
# OpenTelemetry 链路追踪证据脚本（Task #17，一键复跑）
#
# 用法：./scripts/tracing-evidence.sh            # 全流程：环境 + collector + 5 服务 + 取证 + 收尾
#       ./scripts/tracing-evidence.sh stop       # 只停 cloud 服务与 collector
#       ./scripts/tracing-evidence.sh status     # 只看进程/端口状态
#
# 采样链路：micrometer-tracing-bridge-otel（OTel bridge）
#           -> OtlpHttpSpanExporter（OTLP/HTTP）
#           -> otelcol-contrib 0.160.0 :4318/v1/traces（scripts/otelcol-config.yaml）
#           -> debug exporter 原样打印每个 span -> output/tracing_collector.log
# 传播协议：W3C tracecontext（traceparent）。服务日志 pattern 注入 traceId/spanId。
#
# 接收端下载注记（真实排障史，勿删）：
#   GitHub 的 otelcol 二进制用 curl 直连/代理下载均以 exit 23/56 断流收场，
#   curl 对 Maven Central 的流写入同样被掐 —— 系本机沙箱掐断 curl 流写入，
#   非网络问题。python 分块续传下载成功（.workbuddy/tmp/fetch_otelcol.py 思路）。
#
# 证据清单（全部 [evidence] 标记，落盘 output/tracing_evidence.log）：
#   1. 跨 3 服务同一 traceId —— 带 token GET /api/banks：一条 trace 同时含
#      gateway-service（入口+验签调用）/ auth-service（/internal/token/verify）/
#      bank-service（路由下游）的 span
#   2. W3C 传播成立 —— auth 与 bank 的 SERVER span 的 Parent ID 都指向
#      gateway-service 的 span（traceparent 透传 -> 下游 span 由上游上下文派生）
#   3. Feign 传播成立 —— POST /api/practice/sessions：practice-service 存在
#      CLIENT span，bank-service SERVER span 的 Parent ID == 该 CLIENT span ID
#      （依赖 feign-micrometer，无它此链断裂）
#   4. 错误路径同样被追踪 —— 伪造 token 的 401 请求在 collector 日志里有
#      gateway-service span 且 http.response.status_code == 401
#   5. 日志-链路互查 —— bank 服务日志行 [bank-service,<traceId>,<spanId>] 中的
#      traceId 能在 collector 原始日志中反查到同 ID 的 trace（MDC 关联成立）
#
# 前置：MySQL(3307)/Redis(6379)/Kafka(9092) 由 ./scripts/start-evidence-env.sh 负责（幂等）。
# =====================================================================
set -uo pipefail

ROOT="C:/Project/QuestionBank"
OUT="$ROOT/output"
PIDS="$ROOT/.workbuddy/pids"
JAVA="/c/Program Files/Java/jdk-21.0.12/bin/java"
OTLCOL="/c/Users/Allen/tools/otelcol/0.160.0/otelcol-contrib.exe"
OTLCONF="$ROOT/scripts/otelcol-config.yaml"

mkdir -p "$OUT" "$PIDS"
cd "$ROOT"

# 本机所有 curl 打 127.0.0.1：显式绕过全局代理（真实踩过：代理劫持把 000 放大成 20 分钟）
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy
export NO_PROXY='127.0.0.1,localhost'
export no_proxy='127.0.0.1,localhost'

log() { echo "[$(date +%H:%M:%S)] $*"; }
ev()  { echo "[evidence] $*"; }

listening() { (echo > /dev/tcp/127.0.0.1/$1) >/dev/null 2>&1; }

http_code() { # http_code <method> <url> [extra curl args...]
  local m=$1 u=$2; shift 2
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X "$m" --max-time 4 "$@" "$u" 2>/dev/null
}

wait_http() { # wait_http <url> <timeout_seconds> <label>
  local url=$1 timeout=$2 label=$3 i=0
  while [ $i -lt $((timeout * 2)) ]; do
    if [ "$(http_code GET "$url")" = "200" ]; then
      log "$label 就绪（${i}x0.5s）"; return 0
    fi
    sleep 0.5; i=$((i + 1))
  done
  log "ERROR: $label 在 ${timeout}s 内未就绪，查看 $OUT/tracing_*.log"; return 1
}

start_jar() { # start_jar <name> <jar> <xmx> <port>
  local name=$1 jar=$2 xmx=$3 port=$4
  local pidfile="$PIDS/tracing_$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    log "$name 已在运行 (pid $(cat "$pidfile"))，跳过"; return 0
  fi
  if listening "$port"; then
    log "$name 端口 $port 已被占用但无 pid 记录，先按端口强清"
    stop_jar "$name" "$port" || return 1
  fi
  (cd "$ROOT" && "$JAVA" -Xms128m -Xmx"$xmx" -XX:MaxMetaspaceSize=256m \
      -jar "$jar" > "$OUT/tracing_$name.log" 2>&1 & echo $! > "$pidfile")
  log "$name 启动中 (pid $(cat "$pidfile"), port $port)"
}

stop_jar() { # stop_jar <name> <port>
  local name=$1 port=$2
  local pidfile="$PIDS/tracing_$name.pid"
  local pid=""
  [ -f "$pidfile" ] && { pid=$(cat "$pidfile"); kill "$pid" 2>/dev/null; rm -f "$pidfile"; }
  # 停止成功的唯一判据是端口真正释放；SIGTERM 后端口可能几十秒不释放
  local i wpid
  for i in $(seq 1 20); do ! listening "$port" && break; sleep 0.5; done
  if listening "$port"; then
    wpid=$(netstat -ano 2>/dev/null | grep -i listening | grep ":$port " | awk '{print $NF}' | head -1)
    if [ -n "${wpid:-}" ]; then
      log "$name 端口 $port 仍占用（winpid=$wpid），taskkill 强杀"
      taskkill /F /PID "$wpid" >/dev/null 2>&1 || true
      for i in $(seq 1 10); do ! listening "$port" && break; sleep 0.5; done
    fi
  fi
  if listening "$port"; then
    log "ERROR: $name 端口 $port 强杀后仍未释放"; return 1
  fi
  log "$name 已停止（端口 $port 已释放）"
}

start_collector() {
  if [ ! -x "$OTLCOL" ]; then
    log "ERROR: 未找到 otelcol-contrib：$OTLCOL"
    log "  获取方式见 .workbuddy/tmp/fetch_otelcol.py（python 分块续传，curl 会被沙箱掐流）"
    return 1
  fi
  if [ -f "$PIDS/tracing_collector.pid" ] && kill -0 "$(cat "$PIDS/tracing_collector.pid")" 2>/dev/null; then
    log "collector 已在运行 (pid $(cat "$PIDS/tracing_collector.pid"))，跳过"
  elif listening 4318; then
    log "ERROR: 4318 已被占用但无 collector pid 记录，请先手工处理（可能残留旧进程）"
    return 1
  else
    ("$OTLCOL" --config "$OTLCONF" > "$OUT/tracing_collector.log" 2>&1 & echo $! > "$PIDS/tracing_collector.pid")
    log "collector 启动中 (pid $(cat "$PIDS/tracing_collector.pid"), otlp/http :4318)"
  fi
  # collector 配置错误会启动即退：轮询端口确认活体
  local i
  for i in $(seq 1 20); do listening 4318 && break; sleep 0.5; done
  if ! listening 4318; then
    log "ERROR: collector 4318 未监听（配置或二进制问题），日志尾部："
    tail -5 "$OUT/tracing_collector.log" | sed 's/^/  /'
    return 1
  fi
  log "collector 就绪（4318 已监听）"
}

stop_collector() {
  local pidfile="$PIDS/tracing_collector.pid"
  [ -f "$pidfile" ] && { kill "$(cat "$pidfile")" 2>/dev/null; rm -f "$pidfile"; }
  local i wpid
  for i in $(seq 1 10); do ! listening 4318 && break; sleep 0.5; done
  if listening 4318; then
    wpid=$(netstat -ano 2>/dev/null | grep -i listening | grep ":4318 " | awk '{print $NF}' | head -1)
    [ -n "${wpid:-}" ] && taskkill /F /PID "$wpid" >/dev/null 2>&1
  fi
  log "collector 已停止"
}

status_all() {
  local st
  for s in discovery auth bank practice gateway; do
    local port=8761
    case $s in auth) port=8081;; bank) port=8082;; practice) port=8083;; gateway) port=8080;; esac
    st="DOWN"; [ "$(http_code GET "http://127.0.0.1:$port/actuator/health")" = "200" ] && st="UP"
    printf "  %-10s port=%-5s health=%s\n" "$s" "$port" "$st"
  done
  st="DOWN"; listening 4318 && st="UP"
  printf "  %-10s port=%-5s otlp=%s\n" "collector" "4318" "$st"
}

GW="http://127.0.0.1:8080"
JAR="$ROOT/cloud"

start_all() {
  log "== 0. 基础设施（MySQL 3307/Redis/Kafka，幂等）=="
  "$ROOT/scripts/start-evidence-env.sh" start || { log "基础设施启动失败，终止"; return 1; }

  log "== 1. 拉起 OTLP 接收端（otelcol debug exporter）=="
  start_collector || return 1

  log "== 2. 编译 cloud（链路追踪依赖已入四服务 pom）=="
  ./scripts/mvn.sh -B -q -f cloud/pom.xml package -DskipTests || { log "cloud 编译失败，终止"; return 1; }

  log "== 3. 拉起 5 服务 =="
  start_jar discovery "$JAR/discovery-service/target/discovery-service-0.1.0-SNAPSHOT.jar" 256m 8761
  wait_http "http://127.0.0.1:8761/actuator/health" 60 "discovery"

  start_jar auth     "$JAR/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar"         384m 8081
  start_jar bank     "$JAR/bank-service/target/bank-service-0.1.0-SNAPSHOT.jar"         384m 8082
  start_jar practice "$JAR/practice-service/target/practice-service-0.1.0-SNAPSHOT.jar" 384m 8083
  wait_http "http://127.0.0.1:8081/actuator/health" 60 "auth-service"
  wait_http "http://127.0.0.1:8082/actuator/health" 60 "bank-service"
  wait_http "http://127.0.0.1:8083/actuator/health" 60 "practice-service"

  start_jar gateway "$JAR/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar" 320m 8080
  wait_http "$GW/actuator/health" 60 "gateway"
  # 网关就绪 ≠ 路由可用（Eureka 首拉 + 上游注册竞态，实测首批请求撞 503），探测到非 503 才继续
  local i c
  for i in $(seq 1 60); do
    c=$(http_code POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"probe","password":"probe"}')
    if [ "$c" != "503" ] && [ "$c" != "000" ]; then
      log "网关 LB 已收敛（${i}x0.5s，探测返回 $c——鉴权失败是预期）"; break
    fi
    sleep 0.5
  done
}

stop_all() {
  log "== 收尾：停止 cloud 服务与 collector =="
  stop_jar gateway 8080; stop_jar practice 8083; stop_jar bank 8082; stop_jar auth 8081; stop_jar discovery 8761
  stop_collector
}

# ---------------------------------------------------------------------
evidence() {
  log "================ 链路追踪证据开始 ================"
  local j; j() { python -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

  # ---- 制造三类锚点请求 ----
  log "教师登录 -> 建库 -> 出题 -> 发布（每步都经网关，都是被追踪请求）"
  TOKEN=$(curl -s --noproxy '*' --max-time 10 -X POST "$GW/api/auth/login" \
    -H 'Content-Type: application/json' -d '{"username":"teacher","password":"teacher123"}' | j "['token']")
  [ -z "${TOKEN:-}" ] && { log "ERROR: 教师登录失败"; return 1; }

  BANKID=$(curl -s --noproxy '*' --max-time 10 -X POST "$GW/api/banks" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" -d "{\"name\":\"tracing-evidence-$(date +%s)\"}" | j "['id']")
  [ -z "${BANKID:-}" ] && { log "ERROR: 建库失败"; return 1; }
  for i in 1 2 3; do
    curl -s --noproxy '*' --max-time 10 -X POST "$GW/api/banks/$BANKID/questions" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" \
      -d "{\"prompt\":\"题$i：1+1=?\",\"type\":\"SINGLE\",\"options\":[\"1\",\"2\",\"3\",\"4\"],\"answer\":\"$((i%2+1))\"}" >/dev/null
  done
  PAPERID=$(curl -s --noproxy '*' --max-time 15 -X POST "$GW/api/banks/$BANKID/publish" \
    -H "Authorization: Bearer $TOKEN" | j "['paperId']")
  log "bankId=$BANKID paperId=$PAPERID"

  # 锚点 A（证据 1/2）：带 token 读题库列表 -> gateway(验签调用 auth) + gateway(路由) + bank
  log "锚点A：GET /api/banks（教师 token）"
  ACODE=$(http_code GET "$GW/api/banks" -H "Authorization: Bearer $TOKEN")
  # 锚点 B（证据 3）：学生经网关建会话 -> practice --Feign--> bank /internal 快照
  # 先确认 practice-service 已进入 gateway 的 LB 缓存：eureka 首次注册延迟（默认最长
  # 40s）+ LB 缓存刷新（默认 35s）赶不上锚点时刻就是 503——run3 实测 gateway 打出
  # "No servers available for service: practice-service"。这里轮询 eureka apps API
  # 到注册出现（yml 已把注册与缓存刷新调到 5s/2s），再留一个刷新周期余量。
  log "等待 practice-service 注册并进入 gateway LB 缓存"
  PREG=""
  for _ in $(seq 1 30); do
    PREG=$(curl -s --noproxy '*' --max-time 3 -H 'Accept: application/json' \
      "http://127.0.0.1:8761/eureka/apps/PRACTICE-SERVICE")
    echo "$PREG" | grep -q PRACTICE-SERVICE && break
    PREG=""
    sleep 2
  done
  [ -n "$PREG" ] || { log "ERROR: practice-service 60s 内未注册到 eureka"; return 1; }
  sleep 4
  log "锚点B：POST /api/practice/sessions（学生 token，触发 Feign 跨服务）"
  STOKEN=$(curl -s --noproxy '*' --max-time 10 -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"student","password":"student123"}' | j "['token']")
  [ -z "${STOKEN:-}" ] && { log "ERROR: 学生登录失败"; return 1; }
  BCODE=$(http_code POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"trace-$(date +%s)\"}")
  # 锚点 C（证据 4）：伪造 token -> 网关向 auth 验签被拒 -> 401
  log "锚点C：GET /api/banks（伪造 token）"
  CCODE=$(http_code GET "$GW/api/banks" -H 'Authorization: Bearer forged.token.here')
  ev "0.锚点请求返回码：A(带token读库)=$ACODE(期望200) B(Feign建会话)=$BCODE(期望200) C(伪造token)=$CCODE(期望401)"

  # OTLP 导出是批量异步的（BatchSpanProcessor 默认 5s 一批），等一拍再查
  log "等待 span 导出（批量 5s 周期）"
  sleep 12

  # ---- 日志关联锚点：从 bank 服务日志抓 [snapshot] 行（请求路径日志）的 traceId/spanId ----
  # 注意不能抓任意带 trace 的行：OutboxRelay 调度线程的日志（Kafka producer 初始化等）
  # 同样带 [bank-service,traceId,spanId] 前缀（run3 实测抢先命中，traceId 属于后台
  # 调度而非锚点请求），必须以 internalSnapshot 打的 [snapshot] 行为准——它只有锚点
  # B 成功（Feign 到达 bank）才会出现。
  LOGLINE=$(grep '\[snapshot\]' "$OUT/tracing_bank.log" | grep -oE '\[bank-service,[0-9a-f]{32},[0-9a-f]{16}\]' | head -1)
  if [ -z "${LOGLINE:-}" ]; then
    ev "5.日志关联锚点：bank 日志中未找到 [snapshot] 行（锚点 B 未到达 bank？）"
  else
    log "bank 日志锚点行：$LOGLINE"
  fi

  # ---- python 解析 collector 原始日志并做全部断言 ----
  python "C:/Project/QuestionBank/scripts/parse_tracing.py" \
    "$OUT/tracing_collector.log" "${LOGLINE:-NONE}" || return 1

  log "================ 链路追踪证据结束 ================"
}

case "${1:-run}" in
  status) status_all ;;
  stop)   stop_all ;;
  run)
    # 清理上次残留（无 pid 记录的孤儿进程也按端口强清），保证证据打在新代码上
    stop_all >/dev/null 2>&1
    start_all || exit 1
    evidence
    rc=$?
    stop_all
    log "完成。证据日志: $OUT/tracing_evidence.log（本文件）与 $OUT/tracing_collector.log（原始 span）"
    exit $rc
    ;;
  *) echo "用法: $0 [run|status|stop]"; exit 1 ;;
esac
