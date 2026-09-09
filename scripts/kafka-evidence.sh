#!/usr/bin/env bash
# Kafka Outbox E2E evidence: format KRaft storage -> start broker -> run integration test -> log to output/
# NOTE: Kafka 4.0 的 Windows bat 脚本有 classpath 过长问题（"输入行太长"），必须走 Git Bash + .sh 脚本。
set -u
cd "$(dirname "$0")/.."
KAFKA_HOME="${KAFKA_HOME:-/c/Users/Allen/tools/kafka/kafka_2.13-4.0.0}"
LOGDIR=output
mkdir -p "$LOGDIR"

if [ ! -d "$KAFKA_HOME" ]; then
  echo "Kafka not found: $KAFKA_HOME"; exit 1
fi
# Windows JVM 无法解析 MSYS 的 /c/... 路径，必须显式传 Windows 风格路径
SERVER_CFG="C:/Users/Allen/tools/kafka/kafka_2.13-4.0.0/config/server.properties"

# KRaft format (idempotent with --ignore-formatted)
KAFKA_CLUSTER_ID="$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid 2>/dev/null | tail -1 | tr -d '\r\n ')"
echo "cluster id: $KAFKA_CLUSTER_ID"
"$KAFKA_HOME/bin/kafka-storage.sh" format -t "$KAFKA_CLUSTER_ID" -c "$SERVER_CFG" --ignore-formatted 2>&1 | tail -2

# Start broker
"$KAFKA_HOME/bin/kafka-server-start.sh" "$SERVER_CFG" > "$LOGDIR/kafka_server.log" 2>&1 &
KAFKA_PID=$!
echo "broker pid: $KAFKA_PID"

echo "waiting for broker..."
READY=0
for i in $(seq 1 45); do
  if "$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server 127.0.0.1:9092 >/dev/null 2>&1; then
    echo "broker ready (${i}x2s)"; READY=1; break
  fi
  if [ "$i" -eq 45 ]; then
    echo "broker failed to start, last server log:"; tail -20 "$LOGDIR/kafka_server.log"
  fi
  sleep 2
done
if [ "$READY" != "1" ]; then kill "$KAFKA_PID" 2>/dev/null; exit 1; fi

# Run the gated integration test
KAFKA_EVIDENCE=true ./scripts/mvn.sh -B test -Dtest=KafkaOutboxIntegrationTest 2>&1 \
  | tee "$LOGDIR/kafka_evidence_$(date +%Y%m%d_%H%M).log" | grep -E "Tests run|BUILD|outbox|SENT"
RC=${PIPESTATUS[0]}

echo "test exit code: $RC (broker pid=$KAFKA_PID kept running; stop with kill)"
exit $RC
