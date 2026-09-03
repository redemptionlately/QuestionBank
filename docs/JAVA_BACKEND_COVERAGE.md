# Java 后端求职覆盖与真实状态

## 已实现并测试

- Java 21、Maven、Spring Boot、MVC 参数校验、统一异常响应
- 无状态 Bearer 认证、角色授权、资源归属检查
- JPA Entity/Repository、事务、乐观版本字段、悲观行锁
- Flyway V1/V2/V3、MySQL/H2、索引和 EXPLAIN 入口
- 题库版本发布、练习保存、确定性判分、错题聚合、提交幂等和并发回滚
- 本地 TTL cache-aside：`ExpiringCache`；发布写入后主动淘汰（当前淘汰发生在事务提交前，未实现 single-flight/分布式失效）
- 持久化异步任务：`ImportJob`、`ImportJobWorker`，after-commit 调度，任务状态和进度可查询；当前 worker 是确定性基线，不解析真实 PDF
- 固定窗口限流：`RateLimitFilter` 使用进程内 map，key 为远端 IP + URI，超限返回 429/Retry-After；未实现集群共享状态和定期清理
- 请求指标：请求数、失败数、累计耗时；`/api/metrics` JSON 入口，指标只在进程内存中累计，未提供 P95/route/status 维度
- Docker Compose MySQL、可执行 JAR、Spring Boot Actuator、JUnit/MockMvc 集成测试

## 仍是学习材料或扩展项

- Redis 共享缓存、Lua 令牌桶和分布式锁
- Kafka/RabbitMQ、可靠投递、Outbox、死信和消费幂等
- 真实 PDF 解析、对象存储、文件病毒扫描和异步导入产物
- lease/heartbeat/重试恢复、任务调度器和死信表
- Prometheus registry、OpenTelemetry tracing、结构化审计日志
- MyBatis/JDBC 复杂查询、读写分离、分库分表
- OAuth2/OIDC/JWT 刷新与撤销、多租户
- Kubernetes、网关、服务发现、滚动发布和弹性伸缩
- Resilience4j 熔断/隔离舱，完整压测和 JVM/JFR 原始证据

## 求职补强学习日（Day 50-57）

学习目录已新增 16 个文件，覆盖常见 Java 后端 JD 中原路线偏浅的部分：

- Day50：HashMap/ConcurrentHashMap、泛型擦除、反射、注解、record/sealed、序列化边界
- Day51：Spring Boot 自动配置、配置绑定、AOP 代理、Actuator 与 liveness/readiness
- Day52：JDBC、MyBatis、HikariCP、MVCC、事务隔离、N+1 与 EXPLAIN
- Day53：Redis Lua、RabbitMQ/Kafka 投递语义、Outbox、重复消费
- Day54：OAuth2/OIDC/JWT、refresh/revoke、CORS/CSRF、对象级授权与 OWASP
- Day55：TCP/DNS/TLS、Linux 排障、Docker 安全、Kubernetes 发布
- Day56：JVM 类加载/GC、线程池、CompletableFuture、虚拟线程和性能证据
- Day57：分页/API 兼容、容量与 SLO、故障演进和 STAR 面试表达
- Day58：Spring Cloud 服务发现、网关、OpenFeign、熔断和重试预算
- Day59：分布式 ID、分布式锁、Outbox/Saga/TCC、分库分表和一致性取舍
- Day60：Elasticsearch mapping、倒排索引、bool 查询、分片、refresh 和 search_after
- Day61：Java 后端全链路复盘、按 JD 模拟面试和简历事实边界

这些 Day 的 `study.md` 是具体知识与源码/官方资料索引，`tests.md` 是背写验收、变式和面试追问；它们补齐学习范围，但不会把未接入项目的 Redis、MQ、ES、Spring Cloud、Kubernetes 或 OAuth2 写成 M0 已实现功能。

## 简历边界

源代码存在不等于用户掌握。只有能复述数据流、亲手修改核心代码、运行测试、解释失败边界并完成变式，
对应能力才可写入简历。当前工程测试通过证明的是代码状态，不证明学习完成或生产容量。
