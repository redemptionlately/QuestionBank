#!/usr/bin/env bash
# 运行真实 MySQL 证据测试，并把原始输出固化到 output/ 作为面试证据。
# 前置：本地 MySQL 9 已启动，root 密码通过 MYSQL_PASSWORD 传入（默认 783421 仅本机开发库）。
set -uo pipefail

cd /c/Project/QuestionBank
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="output/mysql_evidence_${STAMP}.log"

export MYSQL_EVIDENCE=true
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-783421}"

# surefire 3.5.3 起属性改名：-DfailIfNoSpecifiedTests 已失效，必须用 -Dsurefire.failIfNoSpecifiedTests
./scripts/mvn.sh -B test -Dtest=MysqlRealDbIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$OUT"
STATUS=${PIPESTATUS[0]}

echo "证据日志已写入: $OUT (退出码 $STATUS)"
exit "$STATUS"
