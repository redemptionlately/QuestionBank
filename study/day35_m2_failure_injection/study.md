# MustRemember

- [DatabaseLatencyHealthIndicator.java](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java) :18-43：Actuator 健康探针，`SELECT 1`、500ms 阈值，异常→Health.down；[GlobalExceptionHandler.java](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) :19-59 六分支兜底，统一 ErrorResponse(code,message,requestId,timestamp)，配合 server.error.include-message=never 不外泄 SQL
- app 单体真实容错链：①Redis 挂→[RedisPublishedPaperCache.java](../../app/src/main/java/com/allen/questionbank/cache/RedisPublishedPaperCache.java) readThrough 捕获异常降级回库（failures 计数）；②Kafka 挂→[OutboxRelay.java](../../app/src/main/java/com/allen/questionbank/event/OutboxRelay.java) 事件留 PENDING、失败 requeue、达 max-attempts=5 置 FAILED，发布主事务不阻塞；③[ImportJobRetrySweeper.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java) 指数退避 30<<n 秒封顶 600、最多 3 次；④Kafka 消费重试 2 次/200ms 后进 `qb-events.DLT`
- **app 单体没有 Resilience4j 依赖、没有注解式熔断/Bulkhead**；熔断真实代码只在独立的 cloud/practice-service（BankSnapshotGateway @CircuitBreaker bankService：窗口 5/最少 5/失败率 50%/OPEN 10s/半开 3、TimeLimiter 4s、fallback 快速失败 503），cloud 与 app 不共享代码，面试不得混用
- timeout 限制一次等待，retry 重复请求，circuit breaker 在失败阈值后短路，fallback 返回有限能力，bulkhead 限制线程/连接资源范围；app 侧双异步池（import 2/4/100、general 2/8/200，InfrastructureConfig:22-53）起线程资源隔离的等价作用
- 可重试通常是连接瞬断、限时不可用等瞬态错误；权限、参数、唯一键冲突和数据损坏通常不可重试（OutboxPublisher 序列化失败直接回滚事务即实例）
- 超时预算必须小于上游 SLA；指数重试和多层重试可能形成流量乘法，必须限制次数并加入 jitter
- 熔断器状态机为 `CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN`；滑动窗口统计失败率或慢调用率，OPEN 状态拒绝调用
- 故障注入命令：`docker compose stop mysql` / `start mysql`（compose mysql 服务，宿主 3307）；证据全程 tee/Tee-Object
- 外部源码索引（MustRemember）：[Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) 的状态、failure rate 和 permitted calls 配置

# MustUnderstand

- 故障注入验证已定义的状态转换和恢复路径，不是制造随机事故；每个实验都需要停止条件和回滚方式。项目内可复现实验：OpsComponentsTest（DB 探针 DOWN）、RedisCacheIntegrationTest（Redis 挂降级回库，REDIS_EVIDENCE 门控）、OutboxRelayAndConsumerTest（broker 不可达）、ImportJobRetrySweeperTest（退避重排）
- 进程存活、健康探针、业务可用、数据一致性是四个不同层次；数据库恢复不自动证明业务请求成功；提交接口三层幂等（Redis 锁→FOR UPDATE 行锁→uk_submission_question/submission_key）保证故障后重放等价
- 外部源码索引（MustUnderstand）：[Resilience4j TimeLimiter](https://resilience4j.readme.io/docs/timeout) 与 [Retry](https://resilience4j.readme.io/docs/retry) 的组合顺序和异常传播
- 官方：[Resilience4j](https://resilience4j.readme.io/docs/getting-started)、[Circuit Breaker](https://spring.io/projects/spring-cloud-circuitbreaker)
