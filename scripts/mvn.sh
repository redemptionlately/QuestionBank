#!/usr/bin/env bash
# Windows Git Bash 下 mvnw/only-script 启动失败（classworlds Launcher 找不到，
# 原因是 POSIX->Windows 路径转换未生效）。这里直接调用 wrapper 缓存里已解压的 Maven。
# 用法：./scripts/mvn.sh test | package | -DskipTests package
set -euo pipefail

PROJECT_DIR="C:/Project/QuestionBank"
JAVA_HOME_WIN='C:\Program Files\Java\jdk-21.0.12'

# 自动发现 wrapper 缓存中的 Maven 发行版（取第一个带 boot/plexus-classworlds 的目录）
MVN_HOME="$(find "${HOME%/}/.m2/wrapper/dists/apache-maven-3.9.9" -maxdepth 3 -name 'plexus-classworlds-*.jar' 2>/dev/null | head -1)"
if [[ -z "$MVN_HOME" ]]; then
  echo "ERROR: 未找到 Maven wrapper 缓存，请先执行 ./mvnw 触发下载" >&2
  exit 1
fi
MVN_HOME="$(cd "$(dirname "$(dirname "$MVN_HOME")")" && pwd -W 2>/dev/null || dirname "$(dirname "$MVN_HOME")")"
MVN_HOME="${MVN_HOME//\\//}"

export JAVA_HOME="$JAVA_HOME_WIN"
cd "/c/Project/QuestionBank"

exec java \
  -classpath "${MVN_HOME}/boot/plexus-classworlds-2.8.0.jar" \
  "-Dclassworlds.conf=${MVN_HOME}/bin/m2.conf" \
  "-Dmaven.home=${MVN_HOME}" \
  "-Dmaven.multiModuleProjectDirectory=${PROJECT_DIR}" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
