# MustRemember

- TCP 连接经历三次握手、可靠字节流传输和四次挥手；HTTP/1.1 keep-alive 复用连接但请求仍受队头阻塞影响，HTTP/2 通过多路复用降低连接级队头阻塞。
- DNS 解析通常经历本地缓存、递归解析器和权威服务器；TTL、NXDOMAIN 和连接地址变化会影响故障恢复。
- TLS 通过证书链验证身份并协商会话密钥；证书校验失败、SNI、代理和时钟错误都可能造成连接失败。
- Linux 排障从 `ss/lsof` 看端口和连接、`top/free/iostat` 看资源、`ps/jstack/jcmd` 看进程与线程、`curl` 看 HTTP、`journalctl` 看服务日志开始。
- Docker 镜像是不可变分层，容器是运行实例；健康检查、非 root 用户、只读文件系统、资源限制和信号转发影响服务可靠性。
- Kubernetes Deployment 通过 ReplicaSet 滚动发布，readiness 决定接流量，liveness 决定重启；配置和 Secret 不应写入镜像。
- 项目部署源码索引（MustRemember）：根 `Dockerfile`（两阶段 maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre-noble、appuser 非 root、补装 curl、readiness HEALTHCHECK start-period 40s、exec ENTRYPOINT）、根 `docker-compose.yml`（仅 app/mysql:8.4/redis:7.4-alpine 三服务，宿主端口 8080/3307/6380，external 命名卷，无 Kafka/cloud/otelcol）、`deploy/k8s/app.yaml`（Deployment+Service+HPA，env 内联 DB_URL、secretKeyRef 取 mysql-secret，readiness 30s/阈值24、liveness 60s，HPA 1-3/CPU70%，无 ConfigMap）、`deploy/k8s/mysql.yaml`（Secret mysql-secret、mysql:9.0、emptyDir、mysqladmin ping 探针）、`common/RequestIdFilter.java`（MDC key=requestId、响应头 X-Request-Id）、`resources/logback-spring.xml:6`（pattern 含 [%X{requestId:-}]）、`ops/DatabaseLatencyHealthIndicator.java`（SELECT 1、500ms 阈值）、Flyway `V1~V4`（只加不删的前向兼容迁移）。
- 外部源码索引（MustRemember）：[HTTP RFC 9110](https://www.rfc-editor.org/rfc/rfc9110)、[TLS 1.3 RFC 8446](https://www.rfc-editor.org/rfc/rfc8446)、[Docker best practices](https://docs.docker.com/develop/develop-images/dockerfile_best-practices/)、[Kubernetes probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/)

# MustUnderstand

- 应用超时、连接池超时、TCP 重传和负载均衡超时是不同层；只增加一个 timeout 可能造成连接泄漏或重试风暴。
- DNS、TLS、HTTP、应用线程池和数据库连接池要按时间线排障；“端口能连通”不能证明业务可用。
- 容器健康检查失败、进程退出、readiness 摘除和 liveness 重启的处置不同；滚动发布还要考虑数据库向后兼容和回滚。
- 外部源码索引（MustUnderstand）：[Linux man-pages](https://man7.org/linux/man-pages/)、[Kubernetes Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)、[12-factor config](https://12factor.net/config)
