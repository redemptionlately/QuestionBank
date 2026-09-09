#!/usr/bin/env bash
# 以 3306 业务库启动 app 主服务（读取 scripts/.env.local，不入 git）
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/.env.local" ]; then set -a; source "$DIR/.env.local"; set +a; fi
JAR="$(cygpath -w "$DIR/../app/target/question-bank-m0-0.1.0-SNAPSHOT.jar")"
exec java -jar "$JAR" --server.port="${SERVER_PORT:-8080}"
