# MustRemember

- Java 后端项目的可审计事实链由需求、领域模型、接口契约、事务/并发策略、持久化结构、错误契约和观测证据组成；简历每条 bullet 必须能点到类/测试/日志
- 性能指标必须绑定请求模型、数据规模、并发度、硬件、软件版本、预热、重复次数和统计分位数；缺测量条件的数字没有可比性，压测数字只按 docs/interview/resume_one_page.md 的环境绑定表复述
- 当前实现、已验证行为、尚未实现功能和未来设计分别属于事实、证据、缺口和计划，不能互相替代
- **测试盘子（2026-09-09 实测）**：27 测试类 119 个 @Test，默认构建 90 通过 / 29 环境门禁跳过（KAFKA 2、MYSQL 7、READ_REPLICA 3、REDIS 11、SHARDING 6）/ 0 失败；门控跳过是 Assumption 设计不是失败
- **已实现能力清单（可写简历）**：Redis 缓存双实现（local 默认/Redis，afterCommit 淘汰+故障降级）、Redis 分布式锁（Lua 比对 token 释放+fencing token）、Redis Lua 令牌桶限流、Kafka 事务发件箱（topic qb-events/group question-bank/.DLT、消费幂等、毒丸死信）、读写分离（Routing DataSource+LazyConnectionDataSourceProxy，read-replica profile）、AOP 慢调用切面（200ms）、DB 延迟健康指标（SELECT 1/500ms）、RequestId+MDC、ImportJob 指数退避重试（30<<n 封顶 600、最多 3 次）、提交幂等三层、JPA/MyBatis 双栈、自研 monitor-starter（慢 SQL 阈值 1000ms）、micrometer-registry-prometheus、ArchUnit 5 规则、JVM/JUC 17 证据用例、JaCoCo 80/65/80 门禁与 PIT 变异
- **明确缺口（不能写成已实现）**：MultipartFile/PDF 文本抽取、候选审核流、对象存储（pom 无 S3/MinIO）、事件 eventVersion 治理、lease 租约接管、Resilience4j 熔断/Bulkhead（注解式只在 cloud/）；ShardingSphere 仅 test scope（6 证据）；cloud/ 六服务（Eureka 8761/网关 8080/auth 8081/bank 8082/practice 8083）是独立演示、不与 app 共享代码

# MustUnderstand

- 项目主链路由 HTTP 请求、过滤器链（RequestId→限流→ApiToken）、Controller、Service、Repository/JPA+MyBatis、SQL（Flyway 11 表）、事务提交（afterCommit 才做缓存淘汰/异步触发）和 Jackson 响应序列化依次组成；每一步都对应输入、输出和失败边界（400/401/403/404/409/429/500 统一契约）
- 难点的技术模型由不变量、并发冲突、失败回滚、替代方案、观测证据和残余限制组成；条件装配矩阵（cache.backend/rate-limit.backend/lock.backend/kafka.enabled/import.retry.enabled/@Profile read-replica）必须背熟
- Java 核心、Spring、SQL、并发、测试和项目深度构成普通 Java 后端基础能力；Agent 是建立在这些能力之上的扩展
- 源码索引（MustRemember）：[BankController.java](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) :15-80、[PracticeController.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java) :15-72 的 HTTP 主链路；过滤器链 [RequestIdFilter.java](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java)、[RateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java)、[ApiTokenFilter.java](../../app/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java)
- 源码索引（MustUnderstand）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) :19-124（发布事务/Outbox/afterCommit 缓存）、[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) :18-214（submit :56-57 幂等+FOR UPDATE 判分、wrongBook :105-113 走 MyBatis）；事件侧 [OutboxRelay.java](../../app/src/main/java/com/allen/questionbank/event/OutboxRelay.java) 与 [PaperPublishedEventConsumer.java](../../app/src/main/java/com/allen/questionbank/event/PaperPublishedEventConsumer.java)；简历母版 docs/interview/resume_one_page.md
