#!/usr/bin/env bash
# =====================================================================
# Spring Cloud 拆分端到端证据脚本（一键复跑）
#
# 用法：./scripts/cloud-evidence.sh            # 全流程：拉起环境 + 5 服务 + 取证 + 收尾
#       ./scripts/cloud-evidence.sh status     # 只看当前进程/端口状态
#       ./scripts/cloud-evidence.sh stop       # 只停 cloud 服务
#
# 证据清单（全部带 [evidence] 标记，落盘 output/cloud_evidence.log）：
#   1. 服务注册发现   —— Eureka 注册表里 4 个服务全部 UP
#   2. 网关路由       —— 登录/题库/练习请求经 8080 网关进入对应服务
#   3. 网关统一鉴权   —— 无 token 401；伪造 token 401；token 由 auth-service 验签
#   4. Feign 跨服务   —— practice 建会话时经 bank-service /internal 快照接口
#   5. 幂等           —— 同 clientToken 建会话两次，只产生一个会话
#   6. 判分           —— 提交答案得确定性分数，重复提交不重复计分
#   7. 数据所有权     —— SHOW TABLES：qb_auth/qb_bank/qb_practice 表集合互不相交
#   8. 事务性发件箱   —— 发布后 outbox_event 被 relay 投 Kafka 并标记 sent=1
#   9. 网关限流       —— 令牌桶 2/s、桶 5：第 6 次并发请求起返回 429
#  10. 熔断           —— 杀掉 bank，5 次失败后熔断 OPEN、快速失败；
#                        重启后 HALF_OPEN -> CLOSED 自动恢复
#  11. 越权防护        —— 学生 token 加题/发布/拉答案快照全 403；直连伪造
#                        TEACHER 角色头跨主发布仍 403（服务是最后一道防线）
#
# 前置：MySQL(3307)/Redis(6379)/Kafka(9092) 由 ./scripts/start-evidence-env.sh 负责，
#       本脚本开头会自动调用它（幂等）。
# =====================================================================
set -uo pipefail

ROOT="C:/Project/QuestionBank"
OUT="$ROOT/output"
PIDS="$ROOT/.workbuddy/pids"
JAVA="/c/Program Files/Java/jdk-21.0.12/bin/java"
MYSQL_EXE='C:/Program Files/MySQL/MySQL Server 9.0/bin/mysql.exe'
MYSQL_PASSWORD='783421'

mkdir -p "$OUT" "$PIDS"
cd "$ROOT"

# 本脚本所有 curl 都打本机端口：必须显式绕过全局代理。
# 否则 127.0.0.1 的请求被代理劫走，"连接拒绝"(000) 变成代理的 502，
# wait_http 每轮都吃满 curl 的 max-time，60s 的等待被放大成 20 分钟——真实踩过一次。
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy
export NO_PROXY='127.0.0.1,localhost'
export no_proxy='127.0.0.1,localhost'

log() { echo "[$(date +%H:%M:%S)] $*"; }
ev()  { echo "[evidence] $*"; }

mysql_q() { # mysql_q <port> <sql>
  "$MYSQL_EXE" -uroot -p"$MYSQL_PASSWORD" -h127.0.0.1 -P"$1" --protocol=tcp -e "$2" >/dev/null 2>&1
}

listening() { # listening <port>
  (echo > /dev/tcp/127.0.0.1/$1) >/dev/null 2>&1
}

http_code() { # http_code <method> <url> [extra curl args...]
  local m=$1 u=$2; shift 2
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X "$m" --max-time 4 "$@" "$u" 2>/dev/null
}

cb_state() { # 读 practice 熔断器状态。用正则而非 json 树定位，端点 JSON 结构随 resilience4j 版本变化也不怕
  local S
  S=$(curl -s --noproxy '*' --max-time 5 "http://127.0.0.1:8083/actuator/circuitbreakers" 2>/dev/null | python -c "
import sys, re
d = sys.stdin.read()
m = re.search(r'\"state\"\s*:\s*\"([A-Z_]+)\"', d)
print(m.group(1) if m else 'UNKNOWN')
" 2>/dev/null)
  echo "${S:-UNKNOWN}"
}

wait_http() { # wait_http <url> <timeout_seconds> <label>
  local url=$1 timeout=$2 label=$3 i=0
  while [ $i -lt $((timeout * 2)) ]; do
    if [ "$(http_code GET "$url")" = "200" ]; then
      log "$label 就绪（${i}x0.5s）"; return 0
    fi
    sleep 0.5; i=$((i + 1))
  done
  log "ERROR: $label 在 ${timeout}s 内未就绪，查看 $OUT/cloud_*.log"; return 1
}

start_jar() { # start_jar <name> <jar> <xmx>
  local name=$1 jar=$2 xmx=$3
  local pidfile="$PIDS/cloud_$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    log "$name 已在运行 (pid $(cat "$pidfile"))，跳过"; return 0
  fi
  if listening "$4" 2>/dev/null; then
    log "$name 端口 $4 已被占用但无 pid 记录，跳过启动"; return 0
  fi
  (cd "$ROOT" && "$JAVA" -Xms128m -Xmx"$xmx" -XX:MaxMetaspaceSize=256m \
      -jar "$jar" > "$OUT/cloud_$name.log" 2>&1 & echo $! > "$pidfile")
  log "$name 启动中 (pid $(cat "$pidfile"), port $4)"
}

stop_jar() { # stop_jar <name> <port>
  local name=$1 port=$2
  local pidfile="$PIDS/cloud_$name.pid"
  local pid=""
  [ -f "$pidfile" ] && { pid=$(cat "$pidfile"); kill "$pid" 2>/dev/null; rm -f "$pidfile"; }
  # 停止成功的唯一判据是"端口真正释放"，不是 kill 的退出码。
  # JVM 收到 SIGTERM 走 shutdown hook（Kafka producer 关闭、Eureka 注销、连接池关闭），
  # 端口可能几十秒不释放；熔断证据若在端口未释放时就开打，会全部打在垂死实例上——真实踩过。
  local i wpid
  for i in $(seq 1 20); do ! listening "$port" && break; sleep 0.5; done
  if listening "$port"; then
    # MSYS pid 对原生 java.exe 可能已失效：按端口反查 Windows pid 强杀。
    # 注意必须用单斜杠 /F：本机 MSYS 不做 //F -> /F 的参数转换，taskkill 收到字面
    # "//F" 直接报"无效参数/选项"，又被 >/dev/null 吞掉——上一轮强杀 thus 从未生效（实测踩过）。
    wpid=$(netstat -ano 2>/dev/null | grep -i listening | grep ":$port " | awk '{print $NF}' | head -1)
    if [ -n "${wpid:-}" ]; then
      log "$name 端口 $port 仍占用（winpid=$wpid），taskkill 强杀"
      taskkill /F /PID "$wpid" >/dev/null 2>&1 || log "WARN: taskkill 退出码 $?（winpid=$wpid）"
      for i in $(seq 1 10); do ! listening "$port" && break; sleep 0.5; done
    fi
  fi
  if listening "$port"; then
    log "ERROR: $name 端口 $port 强杀后仍未释放"; return 1
  fi
  log "$name 已停止（端口 $port 已释放）"
}

status_all() {
  for s in discovery auth bank practice gateway; do
    local port=8761
    case $s in auth) port=8081;; bank) port=8082;; practice) port=8083;; gateway) port=8080;; esac
    local st="DOWN"
    if [ "$(http_code GET "http://127.0.0.1:$port/actuator/health")" = "200" ]; then st="UP"; fi
    printf "  %-10s port=%-5s health=%s\n" "$s" "$port" "$st"
  done
}

JAR="$ROOT/cloud"
EUREKA="http://127.0.0.1:8761"
GW="http://127.0.0.1:8080"

start_all() {
  log "== 0. 基础设施（MySQL/Redis/Kafka，幂等）=="
  "$ROOT/scripts/start-evidence-env.sh" start || { log "基础设施启动失败，终止"; return 1; }
  mysql_q 3307 "SELECT 1" || { log "MySQL 3307 不可用，终止"; return 1; }

  log "== 1. 编译 cloud（跳过测试，代码正确性由 CI 与单测负责，这里只要可运行 jar）=="
  ./scripts/mvn.sh -B -q -f cloud/pom.xml package -DskipTests || { log "cloud 编译失败，终止"; return 1; }

  log "== 2. 拉起服务 =="
  start_jar discovery "$JAR/discovery-service/target/discovery-service-0.1.0-SNAPSHOT.jar" 256m 8761
  wait_http "$EUREKA/actuator/health" 60 "discovery"

  start_jar auth     "$JAR/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar"         384m 8081
  start_jar bank     "$JAR/bank-service/target/bank-service-0.1.0-SNAPSHOT.jar"         384m 8082
  start_jar practice "$JAR/practice-service/target/practice-service-0.1.0-SNAPSHOT.jar" 384m 8083
  wait_http "http://127.0.0.1:8081/actuator/health" 60 "auth-service"
  wait_http "http://127.0.0.1:8082/actuator/health" 60 "bank-service"
  wait_http "http://127.0.0.1:8083/actuator/health" 60 "practice-service"

  start_jar gateway "$JAR/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar" 320m 8080
  wait_http "$GW/actuator/health" 60 "gateway"
  # 网关就绪 ≠ 路由可用：Eureka 客户端首拉注册表与上游服务注册完成存在竞态，
  # 首批请求会撞 503（实测：前两轮 sleep 3 侥幸过，第三轮撞上）。探测到非 503 才继续。
  log "等待网关 LB 收敛（探测登录接口，503 = 实例列表未就绪）"
  for i in $(seq 1 60); do
    c=$(http_code POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"probe","password":"probe"}')
    if [ "$c" != "503" ] && [ "$c" != "000" ]; then
      log "网关 LB 已收敛（${i}x0.5s，探测返回 $c——鉴权失败是预期，说明 auth-service 可达）"
      break
    fi
    sleep 0.5
  done
}

stop_all() {
  log "== 收尾：停止 cloud 服务 =="
  stop_jar gateway 8080; stop_jar practice 8083; stop_jar bank 8082; stop_jar auth 8081; stop_jar discovery 8761
}

# ---------------------------------------------------------------------
evidence() {
  log "================ 证据开始 ================"
  local j; j() { python -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

  # ---- 1 注册发现 ----
  ev "1.服务注册发现：Eureka 注册表实例状态"
  curl -s --max-time 10 -H 'Accept: application/json' "$EUREKA/eureka/apps" \
    | python -c "
import sys,json
d=json.load(sys.stdin)
for app in d['applications']['application']:
    inst=app['instance'][0]
    print(f\"  {app['name']:<18} {inst['status']:>4}  {inst['hostName']}:{inst['port']['\$']}\")
" 2>/dev/null || echo "  （解析失败，原始内容见 $OUT/cloud_discovery.log）"

  # ---- 2 登录拿 token ----
  log "通过网关登录（8080 -> auth-service）"
  LOGIN=$(curl -s --max-time 10 -X POST "$GW/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"teacher","password":"teacher123"}')
  TOKEN=$(echo "$LOGIN" | j "['token']")
  USERID_T=$(echo "$LOGIN" | j "['userId']")
  if [ -z "${TOKEN:-}" ]; then log "ERROR: 登录失败: $LOGIN"; return 1; fi
  ev "2.网关路由+登录：HTTP 200，userId=$USERID_T，token 长度=${#TOKEN}"

  # ---- 3 鉴权 ----
  ev "3.网关统一鉴权：无 token -> $(http_code GET "$GW/api/banks")（期望 401）"
  ev "  伪造 token -> $(http_code GET "$GW/api/banks" -H 'Authorization: Bearer forged.token.here')（期望 401）"
  # 直连 bank-service 绕过网关：网关透传的 X-User-Id 头缺失，请求被服务自己拒绝（非 2xx 即正确）
  ev "  绕过网关直连 bank -> $(http_code POST "http://127.0.0.1:8082/api/banks" -H 'Content-Type: application/json' -d '{"name":"x"}')（非 2xx 即被拒）"

  # ---- 4 建库+出题+发布 ----
  log "教师建题库 -> 加 3 题 -> 发布"
  BANK=$(curl -s --max-time 10 -X POST "$GW/api/banks" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $TOKEN" -d '{"name":"cloud-evidence-bank"}')
  BANKID=$(echo "$BANK" | j "['id']")
  [ -z "${BANKID:-}" ] && { log "ERROR: 建库失败: $BANK"; return 1; }
  for i in 1 2 3; do
    curl -s --max-time 10 -X POST "$GW/api/banks/$BANKID/questions" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" \
      -d "{\"prompt\":\"题$i：1+1=?\",\"type\":\"SINGLE\",\"options\":[\"1\",\"2\",\"3\",\"4\"],\"answer\":\"$((i%2+1))\"}" >/dev/null
  done
  PUB=$(curl -s --max-time 15 -X POST "$GW/api/banks/$BANKID/publish" -H "Authorization: Bearer $TOKEN")
  PAPERID=$(echo "$PUB" | j "['paperId']")
  ev "4.出题发布：bankId=$BANKID paperId=$PAPERID $PUB"

  # ---- 5 Feign 跨服务 + 幂等 ----
  log "学生登录 -> 经网关建练习会话（practice --Feign--> bank）"
  STOKEN=$(curl -s --max-time 10 -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"student","password":"student123"}' | j "['token']")
  CTK="evidence-$(date +%s)"
  S1=$(curl -s --max-time 15 -X POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"$CTK\"}")
  SID=$(echo "$S1" | j "['sessionId']")
  S2=$(curl -s --max-time 15 -X POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"$CTK\"}")
  SID2=$(echo "$S2" | j "['sessionId']")
  ev "5.Feign+幂等：两次同 clientToken 建会话 -> sessionId=$SID 与 $SID2（应相等），$S1"

  # ---- 6 判分 ----
  # 标准答案从发布的快照里读（questionId 是自增主键，跨复跑会变，不能写死）
  SNAP=$(curl -s --max-time 10 "$GW/api/banks/papers/$PAPERID/snapshot" -H "Authorization: Bearer $TOKEN")
  ANS=$(python -c "
import json, sys
d = json.loads(sys.argv[1])
print(json.dumps({'answers': {str(i['questionId']): i['answer'] for i in d['items']}}))
" "$SNAP")
  SUB1=$(curl -s --max-time 10 -X POST "$GW/api/practice/sessions/$SID/submit" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $STOKEN" -d "$ANS")
  SUB2=$(curl -s --max-time 10 -X POST "$GW/api/practice/sessions/$SID/submit" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $STOKEN" -d "$ANS")
  ev "6.判分+提交幂等：首提 $(echo "$SUB1" | j "['score']")/$(echo "$SUB1" | j "['total']")，重提 $(echo "$SUB2" | j "['score']")（应与首提一致）"

  # ---- 6b 越权防护（审计修复回归：角色校验 + 所有权校验 + 服务端最后防线）----
  # 锚点 1-3 走网关：学生 token 合法（能过验签），但角色不对，服务端必须自己拦——
  # 快照里带标准答案，这组锚点守住的是"学生从公网拿答案"的通道。
  # 锚点 4 直连 bank 且伪造 X-User-Role: TEACHER：模拟"网关被绕过/配置错误"，验证
  # 服务端不信任透传头之外的东西，所有权（bank.ownerId != userId）仍能兜底。
  ev "6b.服务端越权防护（审计修复回归）："
  C_SEC1=$(http_code POST "$GW/api/banks/$BANKID/questions" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $STOKEN" \
    -d '{"prompt":"越权测试","type":"SINGLE","options":["1","2"],"answer":"1"}')
  ev "  学生 token 给教师题库加题 -> $C_SEC1（期望 403）"
  C_SEC2=$(http_code POST "$GW/api/banks/$BANKID/publish" -H "Authorization: Bearer $STOKEN")
  ev "  学生 token 发布教师题库   -> $C_SEC2（期望 403）"
  C_SEC3=$(http_code GET "$GW/api/banks/papers/$PAPERID/snapshot" -H "Authorization: Bearer $STOKEN")
  ev "  学生 token 拉答案快照     -> $C_SEC3（期望 403）"
  C_SEC4=$(http_code POST "http://127.0.0.1:8082/api/banks/$BANKID/publish" \
    -H 'X-User-Id: 999' -H 'X-User-Role: TEACHER')
  ev "  直连伪造 TEACHER 头跨主发布 -> $C_SEC4（期望 403，所有权校验兜底）"
  if [ "$C_SEC1" = "403" ] && [ "$C_SEC2" = "403" ] && [ "$C_SEC3" = "403" ] && [ "$C_SEC4" = "403" ]; then
    ev "  判定：越权防护生效（4/4 全部 403，角色与所有权双闸都在）"
  else
    ev "  判定：不达标（$C_SEC1/$C_SEC2/$C_SEC3/$C_SEC4，期望全 403）"; return 1
  fi

  # ---- 7 数据所有权 ----
  ev "7.数据所有权（每服务独立 schema，表集合互不相交）："
  for db in qb_auth qb_bank qb_practice; do
    echo "  $db: $("$MYSQL_EXE" -uroot -p"$MYSQL_PASSWORD" -h127.0.0.1 -P3307 --protocol=tcp -N -e \
      "SELECT GROUP_CONCAT(table_name ORDER BY table_name) FROM information_schema.tables WHERE table_schema='$db'" 2>/dev/null)"
  done

  # ---- 8 发件箱 ----
  sleep 4   # 等 relay（2s 周期）投递
  ev "8.事务性发件箱：outbox_event 全部已投递？"
  "$MYSQL_EXE" -uroot -p"$MYSQL_PASSWORD" -h127.0.0.1 -P3307 --protocol=tcp -e \
    "SELECT event_key, sent FROM qb_bank.outbox_event ORDER BY id DESC LIMIT 3" 2>/dev/null | sed 's/^/  /'

  # ---- 9 限流（放在功能性请求之后，避免影响前面的步骤）----
  # 必须同刻并发突发，且不能靠 MSYS 逐个 fork 后台进程：每次 spawn 上百 ms，10 个请求
  # 散布 2s+，令牌 2/s 的补充恰好追平消耗——上一轮就是这么测出 9×200 的。
  # 单个 curl 进程 --parallel --parallel-immediate 同时打开 10 条连接，到达间隔毫秒级。
  # 另踩过：-w 不带 \n 时 10 个结果连成一串，sort/uniq 全部失明——聚合前必须有行边界。
  log "限流证据：单 curl 进程 10 连接并发突发 GET /api/banks（令牌桶 2/s、容量 5）"
  RL_ARGS=()
  # 每个 URL 必须各配一个 -o：只写一个 -o 时 curl 只把它给第一个 URL，
  # 其余响应体全漏进 stdout，污染状态码行结构（实测踩过：5×200+5×429 被数成 200=2）
  for i in $(seq 1 10); do RL_ARGS+=(-o /dev/null); done
  for i in $(seq 1 10); do RL_ARGS+=(-H "Authorization: Bearer $TOKEN" "$GW/api/banks"); done
  RL_RAW=$(curl -s --noproxy '*' --parallel --parallel-immediate --parallel-max 10 \
    -w '%{http_code}\n' --max-time 8 "${RL_ARGS[@]}" 2>/dev/null)
  N200=$(echo "$RL_RAW" | grep -cE '^200$'); N200=${N200:-0}
  N429=$(echo "$RL_RAW" | grep -cE '^429$'); N429=${N429:-0}
  NTOTAL=$(echo "$RL_RAW" | grep -cE '^[0-9]{3}$'); NTOTAL=${NTOTAL:-0}
  CODES=$(echo "$RL_RAW" | grep -E '^[0-9]{3}$' | sort | uniq -c | awk '{printf "%s×%s ", $2, $1}' | sed 's/ $//')
  ev "9.网关限流：10 并发突发 -> $CODES（桶容量 5、补充 2/s，突发过载部分必须被拒）"
  if [ "$NTOTAL" -ne 10 ]; then
    ev "  判定：证据无效（只收到 $NTOTAL/10 个状态码——响应体漏出或请求失败）"; return 1
  elif [ "$N429" -ge 4 ] && [ "$N200" -ge 5 ]; then
    ev "  判定：限流生效（$N200 个 200 + $N429 个 429，到达间隔抖动允许 ±1）"
  else
    ev "  判定：不达标（200=$N200 429=$N429，期望 200>=5 且 429>=4）"; return 1
  fi

  # ---- 10 熔断 ----
  log "熔断证据：杀掉 bank-service，对 practice 连打 6 次建会话"
  stop_jar bank 8082 || { log "ERROR: bank 端口未能释放，熔断证据无效，终止"; return 1; }
  ev "  前置确认：bank 端口 8082 已关闭（连接拒绝），下面 6 次调用打到的是真实故障"
  T0=$(date +%s%N)
  CB_CODES=""
  for i in 1 2 3 4 5 6; do
    c=$(http_code POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"cb-$i-$(date +%s)\"}")
    CB_CODES="$CB_CODES $c"
  done
  T1=$(date +%s%N)
  N503=$(echo "$CB_CODES" | tr ' ' '\n' | grep -c '^503$'); N503=${N503:-0}
  ev "10.熔断触发：6 次调用状态码 =$CB_CODES 总耗时 $(( (T1-T0)/1000000 ))ms（5 次失败进窗口后快速失败，全部 503）"
  ev "  熔断器状态 = $(cb_state)（期望 OPEN）"
  if [ "$N503" -eq 6 ] && [ "$(cb_state)" = "OPEN" ]; then
    ev "  判定：熔断触发（6/6 拒绝、无一穿透到已死的 bank，状态 OPEN）"
  else
    ev "  判定：不达标（503×$N503/6，状态 $(cb_state)，期望全 503 + OPEN）"; return 1
  fi

  log "重启 bank-service，验证自动恢复"
  start_jar bank "$JAR/bank-service/target/bank-service-0.1.0-SNAPSHOT.jar" 384m 8082
  wait_http "http://127.0.0.1:8082/actuator/health" 60 "bank-service"
  # 服务发现收敛：Eureka 拉表(5s) + LoadBalancer 缓存过期(演示调到 5s)。固定 sleep 是在猜，
  # 猜小了探测全 503 还会把 HALF_OPEN 打回 OPEN——改成轮询到首个 200，收敛耗时本身就是证据。
  # 轮询里 OPEN 期的 503 是廉价快速失败，不穿透、不消耗半开名额。
  T0=$(date +%s)
  FIRST=0
  for i in $(seq 1 30); do
    c=$(http_code POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"rc-first-$i-$(date +%s)\"}")
    [ "$c" = "200" ] && { FIRST=$i; break; }
    sleep 3
  done
  T1=$(date +%s)
  if [ "$FIRST" -eq 0 ]; then
    ev "  恢复探测：90s 内服务发现未收敛（最后状态码 ${c:-无}）——不达标"; return 1
  fi
  ev "  恢复收敛：重启后第 $FIRST 次探测（每 3s）拿到首个 200，收敛耗时 $((T1-T0))s，此时熔断器 = $(cb_state)（半开放行中）"
  RC_CODES="$c"
  for i in 1 2; do
    c2=$(http_code POST "$GW/api/practice/sessions" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $STOKEN" -d "{\"paperId\":$PAPERID,\"clientToken\":\"rc-$i-$(date +%s)\"}")
    RC_CODES="$RC_CODES $c2"
  done
  CB_FINAL=$(cb_state)
  ev "  恢复探测：$RC_CODES，最终熔断器状态 = $CB_FINAL（期望 CLOSED）"
  if [ "$CB_FINAL" = "CLOSED" ]; then
    ev "  判定：熔断自动恢复（HALF_OPEN 探测通过 -> CLOSED，后续请求正常）"
  else
    ev "  判定：不达标（最终状态 $CB_FINAL，期望 CLOSED）"; return 1
  fi

  log "================ 证据结束 ================"
}

case "${1:-run}" in
  status) status_all ;;
  stop)   stop_all ;;
  run)
    start_all || exit 1
    evidence
    rc=$?
    stop_all
    log "完成。证据日志: $OUT/cloud_evidence.log 与 $OUT/cloud_*.log"
    exit $rc
    ;;
  *) echo "用法: $0 [run|status|stop]"; exit 1 ;;
esac
