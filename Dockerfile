# 多阶段构建：构建期用完整 JDK + Maven，运行期只带 JRE，镜像更小、攻击面更小。
# 多模块仓库：先拷全部 pom 利用层缓存（依赖没变时不重新下载），再拷 app 源码。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY monitor-starter/pom.xml monitor-starter/
COPY app/pom.xml app/
RUN mvn -B -q dependency:go-offline -DincludeScope=runtime || true

COPY monitor-starter ./monitor-starter
COPY app/src ./app/src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app

# 非 root 运行：容器被攻破时拿不到 root 权限。
# noble 基础镜像不带 curl，而下面 HEALTHCHECK 依赖它——不装的话探针永远失败（CI 镜像冒烟抓出的真缺陷）。
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/app/target/question-bank-m0-*.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

# 容器探针：liveness 决定重启，readiness 决定是否接流量，都走 Spring Boot 的 health group。
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
