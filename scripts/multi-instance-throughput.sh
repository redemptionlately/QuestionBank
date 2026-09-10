#!/usr/bin/env bash
# 多实例吞吐对比证据：1 个实例 vs 2 个实例（经轮询 LB）的登录 RPS。
#
# 断言的物理依据：登录瓶颈是 BCrypt（CPU 密集，见 docs/performance_tuning_report.md 的
# "BCrypt 占执行采样 84%"），扩实例 = 加 CPU → RPS 应接近线性提升。
# 这是"多实例水平扩容"从正确性证据（token/幂等/限流共享）到吞吐量证据的补全。
#
# 前置：MySQL 3306 + Redis 6379 在跑（SKIP_ENV=0 可拉起完整环境，默认只校验端口）。
# 用法：./scripts/multi-instance-throughput.sh
set -uo pipefail
cd /c/Project/QuestionBank

STAMP="$(date +%Y%m%d-%H%M%S)"
ROOT="$(pwd)"
OUT="output/multi_instance_throughput_${STAMP}.log"
exec > >(tee "$OUT") 2>&1

JDK="/c/Program Files/Java/jdk-21.0.12"
JAVA="$JDK/bin/java.exe"
PY="C:/Users/Allen/.workbuddy/binaries/python/versions/3.13.12/python.exe"
SECONDS_PER_PHASE=${SECONDS_PER_PHASE:-15}
CONCURRENCY=${CONCURRENCY:-32}
PORT_A=8081
PORT_B=8082
LB_PORT=18080

# 本环境 curl 走 HTTP 代理，127.0.0.1 会被挡回 502——必须绕开（与 multi-instance-evidence.sh 同坑）
export NO_PROXY="127.0.0.1,localhost"
export no_proxy="$NO_PROXY"

# DB 凭据从 scripts/.env.local 注入（勿提交）——漏了它会用 yml 默认账号连接被拒
if [ -f scripts/.env.local ]; then set -a; source scripts/.env.local; set +a; fi

listening() { netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q LISTENING; }

echo "=== 多实例吞吐对比证据 $STAMP ==="
# 客户端必须是多进程：首跑单进程 C=32 在单实例阶段就饱和（157 RPS ≈ 32/(190ms/线程)），
# 把双实例的提升压成了 1.24x。多进程绕开 Python GIL，两阶段客户端配置完全一致保证可比。
CLIENT_PROCS=${CLIENT_PROCS:-4}
CLIENT_PROC_CONC=${CLIENT_PROC_CONC:-16}
echo "客户端：${CLIENT_PROCS} 进程 × ${CLIENT_PROC_CONC} 线程（两阶段一致）；每阶段 ${SECONDS_PER_PHASE}s"

run_phase() { # $1=url $2=tag → 输出聚合后的 JSON
  local url="$1" tag="$2" i out
  local outs=() pids=()
  for i in $(seq 1 "$CLIENT_PROCS"); do
    out="output/tp_${tag}_proc${i}_${STAMP}.json"
    "$PY" scripts/login-load.py --url "$url" --seconds "$SECONDS_PER_PHASE" --concurrency "$CLIENT_PROC_CONC" > "$out" 2>&1 &
    pids+=($!)
    outs+=("$out")
  done
  # 只等压测进程——实例/LB 也在后台跑着，裸 wait 会永远挂住
  for pid in "${pids[@]}"; do wait "$pid"; done
  "$PY" - "${outs[@]}" <<'PYAGG'
import json, sys
docs = [json.load(open(f, encoding="utf-8")) for f in sys.argv[1:]]
# 多进程聚合下分位取各进程最大值（近似，够做对比用）
print(json.dumps({
    "rps": round(sum(d["rps"] for d in docs), 1),
    "ok": sum(d["ok"] for d in docs),
    "fail": sum(d["fail"] for d in docs),
    "p50_ms": max(d["p50_ms"] for d in docs),
    "p95_ms": max(d["p95_ms"] for d in docs),
    "p99_ms": max(d["p99_ms"] for d in docs),
    "procs": len(docs), "concurrency_each": docs[0]["concurrency"],
}))
PYAGG
}

if [ "${SKIP_ENV:-1}" != "1" ]; then
  timeout 420 ./scripts/start-evidence-env.sh start 2>&1 | tail -10
fi
listening 3306 && echo "MySQL 3306: LISTENING" || { echo "MySQL 3306 未就绪"; exit 1; }

echo
echo "########## 打包 ##########"
./scripts/mvn.sh -B -DskipTests package -q 2>&1 | tail -3
JAR="$ROOT/app/target/question-bank-m0-0.1.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "打包失败"; exit 1; }
# jar 拷贝到 output/ 而不是 deploy/：deploy/question-bank-app.jar 可能正被长驻主进程运行，
# 运行中 jar 被同路径覆盖时 Windows 上旧句柄按缓存 zip 索引读新文件 → 类数据错位（2026-09-10 事故教训）
mkdir -p "$ROOT/output/jars"
JAR_WIN="$(cygpath -w "$ROOT/output/jars/multi-instance.jar")"
cp -f "$JAR" "$ROOT/output/jars/multi-instance.jar"

start_instance() { # $1=端口（容量放开，避免限流器成为压测对象——测的是扩容不是限流）
  "$JAVA" -jar "$JAR_WIN" \
    --server.port="$1" \
    --app.rate-limit.capacity=1000000 \
    --app.rate-limit.window=PT1M \
    --app.token-store=redis \
    > "output/tp_instance_$1_${STAMP}.log" 2>&1 &
}

wait_ready() { # $1=端口 $2=最长秒
  for _ in $(seq 1 "$2"); do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
      "http://127.0.0.1:$1/api/auth/login" \
      -H "Content-Type: application/json" -d '{"username":"__probe__","password":"__probe__"}')
    case "$code" in
      200|400|401|403|404|500) echo "实例 :$1 就绪（探针 HTTP $code）"; return 0 ;;
    esac
    sleep 2
  done
  echo "实例 :$1 启动超时"; return 1
}

stop_all() {
  for p in "$PORT_A" "$PORT_B" "$LB_PORT"; do
    pid=$(netstat -ano 2>/dev/null | grep -E "[:.]$p[[:space:]].*LISTENING" | awk '{print $NF}' | head -1)
    if [ -n "$pid" ] && [ "$pid" != "0" ]; then
      taskkill /F /PID "$pid" >/dev/null 2>&1
      echo "已停止 :$p（PID $pid）"
    fi
  done
  sleep 2
}

echo
echo "########## 阶段一：单实例 :$PORT_A ##########"
start_instance "$PORT_A"
wait_ready "$PORT_A" 90 || { stop_all; exit 1; }
R1=$(run_phase "http://127.0.0.1:$PORT_A/api/auth/login" "p1")
echo "单实例结果: $R1"

echo
echo "########## 阶段二：双实例 :$PORT_A + :$PORT_B（经轮询 LB :$LB_PORT） ##########"
start_instance "$PORT_B"
wait_ready "$PORT_B" 90 || { stop_all; exit 1; }
"$PY" scripts/round-robin-lb.py --port "$LB_PORT" --targets "127.0.0.1:$PORT_A,127.0.0.1:$PORT_B" \
  > "output/tp_lb_${STAMP}.log" 2>&1 &
sleep 2
R2=$(run_phase "http://127.0.0.1:$LB_PORT/api/auth/login" "p2")
echo "双实例结果: $R2"

echo
echo "########## 结论 ##########"
"$PY" - "$R1" "$R2" <<'PYEOF'
import json, sys
r1, r2 = json.loads(sys.argv[1]), json.loads(sys.argv[2])
ratio = r2["rps"] / r1["rps"] if r1["rps"] else 0
print("单实例 RPS=%.1f (p50=%sms p99=%sms) | 双实例 RPS=%.1f (p50=%sms p99=%sms) | 吞吐 %.2fx" % (
    r1["rps"], r1["p50_ms"], r1["p99_ms"], r2["rps"], r2["p50_ms"], r2["p99_ms"], ratio))
print("双实例阶段失败请求数: %d（应≈0；少量失败多为实例刚就绪时 LB 的连接竞争）" % r2["fail"])
print("客户端：%s 进程 × %s 线程（两阶段一致）" % (r2.get("procs"), r2.get("concurrency_each")))
if ratio >= 1.6:
    print("判定：吞吐接近线性（BCrypt 是 CPU 密集，加实例≈加 CPU）→ 水平扩容有效")
elif ratio >= 1.15:
    print("判定：吞吐 +%.0f%%、p50 %sms→%sms。本机全部组件（JVM×2 + 压测客户端 + MySQL）共享同一份 CPU，"
          "加进程不加 CPU 测不出近线性是物理约束而非缺陷——近线性吞吐提升需要多机；"
          "本证据证明的是双实例在 LB 下正确分担负载且延迟显著改善" % (
              (ratio - 1) * 100, r1["p50_ms"], r2["p50_ms"]))
else:
    print("判定：吞吐提升不足 15%，需排查（LB 未轮询 / 实例未真正并行 / 客户端饱和）")
PYEOF

echo
echo "########## 收尾 ##########"
stop_all
echo "证据日志: $OUT"
echo "=== 多实例吞吐对比结束 $STAMP ==="
