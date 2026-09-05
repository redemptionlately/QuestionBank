# MustRemember

- 持久化异步任务的最小状态是 `RECEIVED -> PROCESSING -> SUCCEEDED/FAILED`；任务表必须保存 owner、输入标识、进度、attempt、错误和 created/updated 时间
- HTTP 创建任务返回 `202 Accepted` 和 `Location`，数据库提交成功后再调度 worker；worker 在独立事务中读取、推进并保存状态，避免未提交行被异步线程读不到

- 任务状态通常为 `CREATED -> QUEUED -> RUNNING -> SUCCEEDED`，失败进入 `RETRYING` 或 `FAILED`；字段包括 `attempt`、`nextRunAt`、`leaseUntil`、`lastError`
- worker 领取用条件 `UPDATE ... WHERE status=QUEUED AND next_run_at<=now` 或锁定读取；只有 lease 过期才允许其他 worker 接管。下面的 lease/重试字段属于后续扩展，不是当前 M0 表结构
- 指数退避可写为 `min(maxDelay, base * 2^attempt) + randomJitter`；参数错误、权限错误和损坏文件通常进入死信
- 处理器必须幂等：同一 job 重复执行不能重复创建题库版本、重复扣费或覆盖更新结果
- 消息系统至少要说明 producer confirm、broker 持久化、consumer ack、重试、死信和消费幂等；“发送成功”不等于业务事务和消费完成
- RabbitMQ 常见模型是 exchange-routing key-queue-consumer；Kafka 常见模型是 topic-partition-offset-consumer group；分区顺序只在同一分区内成立
- Outbox 用同一数据库事务写业务事实和待发送事件，再由 publisher 投递；它降低数据库提交与消息发送之间的丢失窗口，但仍需要重复投递和消费幂等
- worker 领取 SQL 的扩展设计形态是（当前项目尚未执行此 SQL）：
  ```sql
  UPDATE import_job SET status='PROCESSING', lease_until=NOW() + INTERVAL 1 MINUTE,
      attempt=attempt+1
  WHERE id=? AND status IN ('RECEIVED','RETRYING') AND next_run_at <= NOW();
  ```
- 外部源码索引（MustRemember）：[Spring TaskExecutor](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) 的 Executor 配置；[Resilience4j Retry](https://resilience4j.readme.io/docs/retry) 的重试参数

# MustUnderstand

- [ImportJobStatus.java](../../src/main/java/com/allen/questionbank/entity/ImportJobStatus.java) 第 1-3 行定义四个当前状态；[ImportJob.java](../../src/main/java/com/allen/questionbank/entity/ImportJob.java) 第 8-39 行保存 owner、输入、状态、进度、attempt、错误、时间戳和 `@Version`，并实现 `start/succeed/fail`
- [ImportJobController.java](../../src/main/java/com/allen/questionbank/controller/ImportJobController.java) 第 16-36 行定义 `POST/GET /api/import-jobs`、`202 + Location` 和查询 DTO；[ImportJobService.java](../../src/main/java/com/allen/questionbank/service/ImportJobService.java) 第 19-31 行在事务中保存任务并用 `afterCommit` 调度
- [ImportJobWorker.java](../../src/main/java/com/allen/questionbank/service/ImportJobWorker.java) 第 10-30 行使用 `@Async("importTaskExecutor")` 和独立 `@Transactional`，只处理 `RECEIVED`，成功置 100%，异常写入 `FAILED`
- [ImportJobRepository.java](../../src/main/java/com/allen/questionbank/repository/ImportJobRepository.java) 第 1-9 行按 `id + ownerId` 查询，形成资源隔离；[V3__async_import_jobs.sql](../../src/main/resources/db/migration/V3__async_import_jobs.sql) 第 1-15 行是任务表、外键和查询索引；[InfrastructureConfig.java](../../src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) 第 11-30 行配置 2/4 线程、100 队列和线程名
- worker 崩溃恢复需要 lease、心跳、超时重置和有限重试；当前实现是可验证的异步状态基线，还没有 lease、重试调度、死信和真实 PDF 解析

- 至少一次投递意味着处理器可能重复运行；“恰好一次”通常只能在局部事务/幂等效果层面近似
- 任务表是事实来源，线程池只是执行资源；进程崩溃由 lease、心跳、有限重试和死信恢复
- 外部源码索引（MustUnderstand）：[Spring Batch retry](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/retry-logic.html) 的 retry/skip 状态边界
- 外部源码索引（MustRemember）：[RabbitMQ Publisher Confirms](https://www.rabbitmq.com/docs/confirms)、[Kafka Consumer Commit](https://kafka.apache.org/documentation/#consumerconfigs_enable.auto.commit)
- 外部源码索引（MustUnderstand）：[Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html) 的提交、发布、重复和顺序边界
- 官方：[Spring Batch](https://docs.spring.io/spring-batch/reference/)、[Retry](https://resilience4j.readme.io/docs/retry)
