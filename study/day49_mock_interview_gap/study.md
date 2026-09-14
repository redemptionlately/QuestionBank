# MustRemember

- Java 后端基础域包括对象模型、泛型、集合、异常、I/O、JUC、JMM、JVM、Spring MVC、Security、事务、JPA、MySQL、索引、隔离级别、HTTP、Linux、测试和算法
- 项目问题的技术回答结构是业务问题、领域模型、请求数据流、事务/锁、数据库事实、错误响应和证据边界
- 并发题的核心术语包括可见性、原子性、有序性、锁互斥、CAS、连接池、线程池、超时、重试和幂等
- `HashMap` 的定位依赖扰动后的 hash 与 `(n - 1) & hash`；扩容会重新分桶，碰撞链过长可能树化；`ConcurrentHashMap.computeIfAbsent` 只保证该容器操作语义
- `volatile` 保证读写可见性和特定有序性，不保证 `count++` 的复合原子性；`synchronized` 同时提供互斥与 happens-before
- JVM 堆保存对象，线程栈保存栈帧，Metaspace 保存类元数据；GC 回收不可达对象，不按变量离开作用域立即释放
- `try-with-resources` 依赖 `AutoCloseable.close()` 自动释放资源；关闭异常作为 suppressed exception 附着在主异常上
- 求职补强的高频后端域还包括 Spring AOP/代理、JDBC/连接池、MyBatis、RabbitMQ/Kafka、TCP/DNS/TLS、Linux 排障、API 分页与兼容性；这些内容必须能结合 M0 业务解释，而不是只背产品名
- 生产工程基础包括配置与密钥分离、容器健康检查、滚动发布、数据库兼容迁移、CI 中的编译/测试/静态检查和回滚；不会部署生产环境时只能说明原理与验证边界
- Git 面试与日常协作至少掌握 `status/diff/log/branch/merge/rebase/cherry-pick` 的对象和风险；提交应小而可回滚，冲突解决后必须重新测试
- CI 流水线的最小顺序是依赖缓存 -> 编译 -> 单元/集成测试 -> 静态检查 -> 打包镜像 -> 发布；密钥通过受控变量注入，失败阶段阻止后续发布
- 常见设计模式要能解释问题而非背名称：Strategy 封装判分规则，Factory 选择实现，Decorator 增加横切行为，Template Method 固定流程骨架，Observer/事件解耦通知
- 项目已实现的能力边界（2026-09 重构后）：本地默认实现有 `RateLimitFilter` 固定窗口限流、`LocalPublishedPaperCache`（委托 `ExpiringCache` 单实例 TTL cache-aside）、`ImportJob` 持久化异步状态 + `ImportJobRetrySweeper` 退避重试、`RequestMetrics` 原子指标；条件装配的真实分布式能力有 Redis 缓存（`RedisPublishedPaperCache`）、Redis 锁（`RedisLockService`，含 fencing token）、Redis Lua 令牌桶限流（`RedisTokenBucketRateLimiter`/`RedisRateLimitFilter`）、Kafka Outbox（event 包 8 类，topic=qb-events、group=question-bank、.DLT、consumed_event 幂等表）、读写分离（read-replica profile）、Prometheus（micrometer-registry-prometheus，/actuator/prometheus）。仍是外部设计/未实现：Redis 集群与 Redlock、Kafka 精确一次、ShardingSphere 生产接入（仅 test scope 证据测试）、多实例自动扩容实测
- 面试复盘的项目源码索引：`BankService.java:38-96`（发布事务、:74-77 同事务写 Outbox、:87-96 afterCommit 淘汰缓存）、`BankService.java:100-124`（只读事务与缓存读路径 getOrLoad）、`ImportJobController.java:20-26`（异步 HTTP 契约）、`ImportJobService.java:17-30`（事务提交后 afterCommit 调度 worker）、`ImportJobWorker.java:12-24`（@Async 独立事务状态推进）、`ImportJobRetrySweeper.java:43-63`（定时扫描 + 30/60/120/600 指数退避）、`common/RateLimitFilter.java:37-65`（本地固定窗口与 429）、`redis/RedisTokenBucketRateLimiter.java:25-88`（Lua 令牌桶）、`event/OutboxRelay.java:46-69`（PENDING 轮询投递与 markFailed/requeue）、`MetricsController.java:13`（原子指标出口）、`V3__async_import_jobs.sql:1-15`、`V4__outbox_events.sql:5-27`（outbox_event + consumed_event）。回答时必须说明：本地实现默认装配、Redis/Kafka/读写分离靠配置键与 profile 条件开启，其证据集成测试由 `*_EVIDENCE` 环境变量门控，默认构建跳过而非失败
- 外部源码索引（MustRemember）：[Java Collections API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Collection.html)、[CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)、[Spring `@Transactional`](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

# MustUnderstand

- Java 语义问题关注语言规则，Spring 问题关注代理与容器，SQL 问题关注计划与隔离，并发问题关注共享状态和失败边界
- checked exception 与 unchecked exception 的传播契约不同；事务、线程池、缓存和消息系统都必须说明失败后的事实状态
- 面试中的“项目做了什么”必须落到类、方法、表、索引、测试和日志；未实现/未测量内容只能作为设计，不是经历
- 面试回答必须区分：M0 业务闭环已由 H2/上下文测试验证（默认构建 90 个 @Test 通过、0 失败）；Redis/Kafka/真 MySQL/读写分离/分库分表的证据测试代码齐备但由环境变量门控（默认 29 个跳过），跑过才能声称实测；ShardingSphere 依赖仅 test scope、生产 classpath 不含；cloud/ 是独立微服务演示，与 app 不共享代码，不能混为一谈
- 外部源码索引（MustUnderstand）：[Java Language Specification](https://docs.oracle.com/javase/specs/jls/se21/html/)、[Spring Framework reference](https://docs.spring.io/spring-framework/reference/)、[MySQL 8.4 Reference](https://dev.mysql.com/doc/refman/8.4/en/)
- 外部源码索引（MustRemember）：[Spring AOP](https://docs.spring.io/spring-framework/reference/core/aop.html)、[JDBC API](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/module-summary.html)、[RabbitMQ Tutorials](https://www.rabbitmq.com/getstarted.html)
- 外部源码索引（MustUnderstand）：[OWASP API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x00-header/)、[Kubernetes Probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/)
- 外部源码索引（MustRemember）：[Git reference](https://git-scm.com/docs)、[GitHub Actions Java workflow](https://docs.github.com/en/actions/automating-builds-and-tests/building-and-testing-java-with-maven)
- 外部源码索引（MustUnderstand）：[12-factor config](https://12factor.net/config) 的配置与凭据边界；[Kubernetes Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/) 的滚动发布与回滚
- 官方：[Java 21 API](https://docs.oracle.com/en/java/javase/21/)、[Spring Guides](https://spring.io/guides)、[MySQL 8.4 Reference](https://dev.mysql.com/doc/refman/8.4/en/)
