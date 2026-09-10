#!/usr/bin/env bash
# 以 3306 业务库启动 app 主服务。
# 运行 jar 固定使用 deploy/question-bank-app.jar（target/ 会被外部构建周期性清理，
# 不直接依赖它）；缺失时自动从 target 拷贝或现场打包。
# 数据库连接参数读 scripts/.env.local（勿提交）。
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

if [ -f "$DIR/.env.local" ]; then set -a; source "$DIR/.env.local"; set +a; fi

STABLE_JAR="$ROOT/deploy/question-bank-app.jar"
TARGET_JAR="$ROOT/app/target/question-bank-m0-0.1.0-SNAPSHOT.jar"

# 保护：8080 已有进程在跑时，deploy jar 可能就是它正在运行的文件——不要 cp 覆盖
#（运行中 jar 被同路径覆盖 → Windows 旧句柄按缓存 zip 索引读新文件 → 类数据错位，2026-09-10 事故教训）
if ! netstat -ano 2>/dev/null | grep -q ":8080 .*LISTENING"; then
  if [ ! -f "$STABLE_JAR" ]; then
    if [ ! -f "$TARGET_JAR" ]; then
      echo "[build] 打包 jar（跳过测试）…"
      (cd "$ROOT" && ./scripts/mvn.sh -B -DskipTests package -q)
    fi
    cp "$TARGET_JAR" "$STABLE_JAR"
    echo "[init] 已固化 jar → deploy/question-bank-app.jar"
  fi
else
  echo "[warn] 8080 已有服务在监听，跳过 jar 固化（避免覆盖运行中 jar）"
fi

exec java -jar "$(cygpath -w "$STABLE_JAR")" --server.port="${SERVER_PORT:-8080}"
