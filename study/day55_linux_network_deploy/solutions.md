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
| 服务日志 | `journalctl -u app -f`、应用日志（requestId） | 异常栈、慢 SQL、下游错误 |
结论：连接拒绝=端口没监听/进程挂；连接超时=网络不通/防火墙/丢包；TLS 失败=证书/时钟/SNI；429=被限流；500=应用异常；锁等待=DB 层，互不混淆。

---

### Q2. Dockerfile 要点与 Deployment readiness/liveness。
**Dockerfile 安全与可靠要点**：
```dockerfile
FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r app && useradd -r -g app app          # 非 root 运行
WORKDIR /app
COPY target/question-bank-m0-0.1.0-SNAPSHOT.jar app.jar
USER app                                              # 降权
EXPOSE 8080
HEALTHCHECK --interval=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
配合：只读根文件系统（`readOnly:true` + 可写 emptyDir）、`mem/cpu limits` 资源限制、正确的 STOPSIGNAL/exec 形式让 JVM 收到 SIGTERM 优雅停机；镜像不可变分层、配置与 Secret 不打进镜像。

**K8s 探针**：
```yaml
readinessProbe:   # 失败→从 Service 摘除，不接流量（不重启）
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 20
livenessProbe:    # 失败→重启容器（只探测内部存活，不依赖 DB，防重启风暴）
  httpGet: { path: /actuator/health/liveness, port: 8080 }
```
Deployment 经 ReplicaSet 滚动更新（maxSurge/maxUnavailable 控制节奏），readiness 通过才接流量。

---

### Q3. 画一次 HTTPS 请求从 DNS 到 Controller 的时间线。
```
浏览器/客户端
 1) DNS 解析：本地缓存→递归解析器→权威服务器，得到 IP
 2) TCP 三次握手：SYN / SYN-ACK / ACK（1 RTT）
 3) TLS 握手：ClientHello(SNI)→证书→密钥协商（TLS1.3 1 RTT）
 4) 反向代理(Nginx)：TLS 终止、X-Forwarded-*、路由、超时
 5) 应用：过滤器链(限流→token→授权)→DispatcherServlet→Controller
 6) Service→Repository→连接池取连接→MySQL（SQL/锁）
 7) 原路返回：Jackson 序列化→HTTP 响应→TCP
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
采用 expand/contract：① 扩展：新版本迁移只加可空列/新表（旧代码不受影响），灰度新代码双写/兼容读；② 回填历史数据；③ 稳定后再收紧约束/删除旧列（contract）。滚动期间新旧实例并存，因此每一步必须对另一版本兼容；回滚即切回旧镜像，因迁移是前向兼容（只加不删），旧代码仍能正常工作。禁止在同一次发布里重命名/删除正在使用的列。

### E3. keep-alive、HTTP/2 多路复用、连接池关系。
HTTP/1.1 keep-alive 复用一条 TCP 连接发多个请求，但同一连接上请求-响应串行，存在**队头阻塞**（前一个慢请求挡住后面）；浏览器用多连接缓解。HTTP/2 在一条连接上用多个 stream **多路复用**，应用层请求可并发、不再有 HTTP 层队头阻塞（TCP 层丢包仍会阻塞所有 stream）。服务端/客户端连接池（HTTP client、JDBC）复用连接以摊薄握手成本，但池大小受下游容量约束，复用不等于无限并发。
