#!/usr/bin/env bash
# Kafka 3 节点 KRaft 集群高可用实验 v2：
# 集群组建 -> RF=3/min.isr=2 主题 -> 100 条 -> 杀 node1 -> leader 选举 + ISR 收缩 + 继续投递 50 条 ->
# 用 GetOffsetShell 分区末端位移做权威计数验证零丢失。结束前优雅停机。
set -u
cd /c/Project/QuestionBank
KAFKA_HOME="/c/Users/Allen/tools/kafka/kafka_2.13-4.0.0"
CFGDIR="$KAFKA_HOME/config/cluster-demo"
CLUSTER_ID="QGE8oKypR3eU_FL8dh7DHg"
STAMP=$(date +%Y%m%d_%H%M)
OUT="output/kafka_cluster_ha2_${STAMP}.log"

declare -A CLIENT_PORT=( [0]=9092 [1]=9093 [2]=9094 )
declare -A CTRL_PORT=(   [0]=19092 [1]=19093 [2]=19094 )

# ---------- 1. 重新格式化（干净元数据）----------
declare -A NODE_PID
for N in 0 1 2; do
  rm -rf "/c/tmp/kafka-cluster-logs-$N"
  "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CLUSTER_ID" \
    -c "C:/Users/Allen/tools/kafka/kafka_2.13-4.0.0/config/cluster-demo/server-$N.properties" >/dev/null 2>&1
done
echo "1/7 三节点存储已重新格式化"

# ---------- 2. 同时启动 ----------
for N in 0 1 2; do
  "$KAFKA_HOME/bin/kafka-server-start.sh" \
    "C:/Users/Allen/tools/kafka/kafka_2.13-4.0.0/config/cluster-demo/server-$N.properties" \
    > "output/kafka_cluster_node$N.log" 2>&1 &
  NODE_PID[$N]=$!
done
echo "2/7 三节点启动中..."
for i in $(seq 1 45); do
  if "$KAFKA_HOME/bin/kafka-metadata-quorum.sh" --bootstrap-server localhost:9092 describe --status >/dev/null 2>&1; then
    echo "集群就绪（第 $i 次探测）"; break
  fi
  [ "$i" = 45 ] && { echo "集群未就绪，放弃"; exit 1; }
  sleep 2
done

# ---------- 3. 建主题 RF=3 min.isr=2 ----------
"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --create \
  --topic qb-ha --partitions 3 --replication-factor 3 \
  --config min.insync.replicas=2 >/dev/null 2>&1
echo "3/7 主题 qb-ha 已创建（3 分区 / RF=3 / min.isr=2）"
"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --describe --topic qb-ha 2>/dev/null | tee -a "$OUT"

# ---------- 4. 正常期投递 100 条并核对位移 ----------
for i in $(seq 1 100); do echo "msg-$i"; done | \
  "$KAFKA_HOME/bin/kafka-console-producer.sh" --bootstrap-server localhost:9092 --topic qb-ha 2>/dev/null
BEFORE=$("$KAFKA_HOME/bin/kafka-get-offsets.sh" --bootstrap-server localhost:9092 --topic qb-ha 2>/dev/null \
  | awk -F: '{s+=$3} END {print s}')
echo "4/7 正常期投递后分区末端位移合计: $BEFORE（预期 100）" | tee -a "$OUT"

# ---------- 5. 故障注入：强杀 node 1 ----------
kill -9 "${NODE_PID[1]}" 2>/dev/null
sleep 8
"$KAFKA_HOME/bin/kafka-metadata-quorum.sh" --bootstrap-server localhost:9092 describe --status 2>/dev/null | grep -aE "LeaderId|CurrentVoters" | tee -a "$OUT"
echo "--- 故障后副本分布（观察 leader 迁移与 ISR 收缩）---" | tee -a "$OUT"
"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --describe --topic qb-ha 2>/dev/null | tee -a "$OUT"

# ---------- 6. 故障期经 node 2 继续投递 50 条 ----------
for i in $(seq 101 150); do echo "msg-$i"; done | \
  "$KAFKA_HOME/bin/kafka-console-producer.sh" --bootstrap-server localhost:9094 --topic qb-ha 2>/dev/null
AFTER=$("$KAFKA_HOME/bin/kafka-get-offsets.sh" --bootstrap-server localhost:9092 --topic qb-ha 2>/dev/null \
  | awk -F: '{s+=$3} END {print s}')
echo "6/7 故障期投递后分区末端位移合计: $AFTER（预期 150）" | tee -a "$OUT"

# ---------- 7. 零丢失结论 + 优雅停机 ----------
if [ "$BEFORE" = "100" ] && [ "$AFTER" = "150" ]; then
  echo "7/7 结论: broker 宕机期间消息零丢失（100 -> 150 全部落盘），集群自动选主继续服务" | tee -a "$OUT"
  RESULT=0
else
  echo "7/7 结论: 位移计数异常（before=$BEFORE after=$AFTER），需排查" | tee -a "$OUT"
  RESULT=1
fi
kill -TERM "${NODE_PID[0]}" "${NODE_PID[2]}" 2>/dev/null
sleep 8
echo "证据已保存: $OUT"
exit $RESULT
