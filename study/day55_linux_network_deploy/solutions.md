# Day55 Linux, Network & Deploy · 题目与标准解答（Solutions）

> 网络协议、Linux 排障、Docker/K8s 部署。

## Current

### Q1. “请求超时”分层排查命令与证据。
按时间线从外到内逐层定位，每层都要拿到证据再进入下一层：
| 层 | 命令 | 看什么证据 |
|---|---|---|
| DNS | `nslookup host` / `dig host`、`getent hosts` | 是否解析出 IP、TTL、解析耗时 |
| TCP 端口/连接 | `ss -lntp`、`lsof -i:8080`、`nc -vz host port` | 是否监听、连接状态(SYN-SENT/ESTAB)、拒绝还是超时 |
| TLS | `curl -v https://host`、`openssl s_client -connect host:443 -servername host` | 证书链、过期、SNI、握手阶段 |
| HTTP | `curl -v -w '%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer}\n'` | 各段耗时、状态码 |
| 进程/线程 | `ps -ef`、`jstack/jcmd <pid> Thread.print` | 线程是否阻塞、线程池是否耗尽 |
| 系统资源 | `top`、`vmstat 1`、`free -h`、`iostat -x 1` | CPU 运行队列、内存/swap、磁盘 await |
| 服务日志 | `journalctl -u app -f`、应用日志（按 requestId 串联） | 异常栈、慢 SQL、下游错误 |
结论：连接拒绝=端口没监听/进程挂；连接超时=网络不通/防火墙/丢包；TLS 失败=证书/时钟/SNI；429=被限流；500=应用异常；锁等待=DB 层，互不混淆。本项目 RequestIdFilter 为每个请求生成/透传 X-Request-Id 并写入 MDC（key 固定 "requestId"），logback pattern `[%X{requestId:-}]` 让同请求跨类日志可 grep 串联，响应头回写同一 id，ErrorResponse 也带 requestId 字段。

---

### Q2. Dockerfile 要点与 Deployment readiness/liveness。
**本项目真实 Dockerfile（根目录，两阶段）**：build 阶段基于 `maven:3.9-eclipse-temurin-21`，先 COPY 三个 pom 跑 dependency:go-offline 利用层缓存，再 COPY 源码 `-DskipTests package`；runtime 阶段基于 `eclipse-temurin:21-jre-noble`：
```dockerfile
# 运行阶段（与项目实际一致的关键点）
RUN useradd -r -m -u 1001 appuser && apt-get update \
 && apt-get install --no-install-recommends -y curl && rm -rf /var/lib/apt/lists/*  # noble 不带 curl，不装则健康检查永远失败
COPY --from=build /workspace/app/target/question-bank-m0-*.jar /app/app.jar
USER appuser                                        # 非 root 运行
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]   # exec 形式让 java 成 1 号进程，收到 SIGTERM 优雅停机
```
配合：`mem/cpu limits` 资源限制、exec 入口保证信号直达 JVM；镜像不可变分层、配置与 Secret 不打进镜像。

**K8s 探针（deploy/k8s，真实清单：[app.yaml](../../deploy/k8s/app.yaml)=Deployment+Service+HPA，[mysql.yaml](../../deploy/k8s/mysql.yaml)=Secret+Deployment+Service，没有 ConfigMap——配置走 env 内联 + Secret mysql-secret）**：
```yaml
readinessProbe:   # 失败→从 Service 摘除，不接流量（不重启）
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 30
  periodSeconds: 5
  failureThreshold: 24          # 给 MySQL 启动/Flyway 迁移留足窗口
livenessProbe:    # 失败→重启容器（只探内部存活，不依赖 DB，防重启风暴）
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 60
  periodSeconds: 10
```
Deployment replicas=1、镜像 question-bank:ci（IfNotPresent）、resources requests 500m/512Mi、limits 1500m/1Gi；Service 8080 ClusterIP；HPA min 1/max 3、CPU 平均 70%（依赖 metrics-server）。配套 mysql Deployment 用 mysql:9.0 + emptyDir（Pod 重启丢数据，注释明确生产应换 StatefulSet+PVC+主从）。另注意根 [docker-compose.yml](../../docker-compose.yml) 只含 app/mysql(mysql:8.4)/redis(redis:7.4-alpine，宿主避让端口 3307/6380) 三服务，**没有 Kafka、没有 cloud 微服务、没有 otelcol**——cloud 演示环境由 scripts 另起。

---

### Q3. 画一次 HTTPS 请求从 DNS 到 Controller 的时间线。
```
浏览器/客户端
 1) DNS 解析：本地缓存→递归解析器→权威服务器，得到 IP
 2) TCP 三次握手：SYN / SYN-ACK / ACK（1 RTT）
 3) TLS 握手：ClientHello(SNI)→证书→密钥协商（TLS1.3 1 RTT）
 4) 反向代理(Nginx)：TLS 终止、X-Forwarded-*、路由、超时
 5) 应用：过滤器链(RequestIdFilter 最先 → ApiTokenFilter/授权 → 限流)→DispatcherServlet→Controller
 6) Service（ServiceLatencyAspect 计时、声明式事务）→Repository/Mapper→连接池取连接→MySQL（SQL/锁；read-replica profile 下只读事务路由从库）
 7) 原路返回：Jackson 序列化 record DTO→响应头回写 X-Request-Id→HTTP 响应→TCP
```
每段独立计时（curl -w），不能把总耗时都归因于应用代码。

---

## External

### E1. 区分连接拒绝/超时/TLS 失败/429/500/DB 锁等待。
- Connection refused：对端主机可达但端口无监听（进程未起/端口错），立即返回 RST；
- Connection timed out：SYN 无响应（安全组丢包/网络隔离），等到超时；
- TLS 校验失败：证书过期/链不全/域名不匹配/客户端时钟错误，握手阶段失败；
- HTTP 429：TCP/TLS/HTTP 都正常，被应用/网关限流，看 Retry-After；
- HTTP 500：请求到达应用但内部异常，看应用日志与 requestId；
- DB 锁等待：应用线程 RUNNABLE 但卡在 JDBC read，`innodb_trx` 可见锁等待，表现为响应慢而非连不上。

### E2. schema 向后兼容的滚动发布与回滚步骤。
采用 expand/contract：① 扩展：新版本迁移只加可空列/新表（旧代码不受影响），灰度新代码双写/兼容读；② 回填历史数据；③ 稳定后再收紧约束/删除旧列（contract）。滚动期间新旧实例并存，因此每一步必须对另一版本兼容；回滚即切回旧镜像，因迁移是前向兼容（只加不删），旧代码仍能正常工作。禁止在同一次发布里重命名/删除正在使用的列。本项目 Flyway 迁移即按此组织：V1 建 7 表、V2 只加索引、V3/V4 各加新表（import_job、outbox_event/consumed_event），没有对既有列的破坏性变更，且 ddl-auto=validate 防止实体与 schema 悄悄漂移。

### E3. keep-alive、HTTP/2 多路复用、连接池关系。
HTTP/1.1 keep-alive 复用一条 TCP 连接发多个请求，但同一连接上请求-响应串行，存在**队头阻塞**（前一个慢请求挡住后面）；浏览器用多连接缓解。HTTP/2 在一条连接上用多个 stream **多路复用**，应用层请求可并发、不再有 HTTP 层队头阻塞（TCP 层丢包仍会阻塞所有 stream）。服务端/客户端连接池（HTTP client、JDBC）复用连接以摊薄握手成本，但池大小受下游容量约束，复用不等于无限并发。
