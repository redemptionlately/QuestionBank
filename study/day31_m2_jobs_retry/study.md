# MustRemember

- 持久化异步任务最小状态 `RECEIVED -> PROCESSING -> SUCCEEDED/FAILED`（ImportJobStatus 只有这四态，无 RETRYING）；任务表保存 owner、输入标识、进度、attempt、error 和 created/updated 时间，V3 全 11 列
- HTTP 创建任务返回 `202 Accepted` + `Location`（ImportJobController:20-24），事务 afterCommit 才调度 worker（ImportJobService:26-28）；worker 独立事务、@Async("importTaskExecutor")、只处理 RECEIVED（ImportJobWorker:12-27）
- **重试清扫已真实实现**：ImportJobRetrySweeper（:26-65）默认开启（app.import.retry.enabled，matchIfMissing=true），@Scheduled fixedDelay `${app.import.retry.sweep-ms:60000}`；候选 = FAILED 且 attempt<MAX_ATTEMPTS(=3)；退避 `30×2^(n-1)` 秒封顶 600s，以 updatedAt 为起点在内存过滤窗口；job.retry() 翻回 RECEIVED 后跨 bean 调 worker.process（@Async 代理才生效）
- worker 领取的多实例设计形态是条件 UPDATE（租约 leaseUntil，**未实现**）；当前真实等价物是定时扫表 + @Version 乐观锁防双入队，没有 lease/心跳/nextRunAt 列
- 指数退避通式 `min(maxDelay, base * 2^attempt) + randomJitter`；参数错误、权限错误、损坏文件这类不可恢复失败应直接终态；attempt 耗尽保持 FAILED 等人工，不无限重试
- 处理器必须幂等：状态门禁（只处理 RECEIVED）+ 业务唯一键，同一 job 重复执行不能重复创建题库版本或覆盖他人结果
- 消息系统至少说明 producer confirm、broker 持久化、consumer ack、重试、死信和消费幂等；项目真实栈是 Transactional Outbox + Kafka（topic qb-events、group question-bank、重试 2 次后进 .DLT、消费端 consumed_event 幂等），RabbitMQ 仅为通用知识未使用
- worker 条件领取 SQL 的设计形态（当前项目未执行此 SQL，真实恢复走 Sweeper 扫表）：
  ```sql
  UPDATE import_job SET status='PROCESSING', lease_until=NOW() + INTERVAL 1 MINUTE,
      attempt=attempt+1
  WHERE id=? AND status IN ('RECEIVED','RETRYING') AND next_run_at <= NOW();
  ```
- 外部源码索引（MustRemember）：[Spring TaskExecutor](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) 的 Executor 配置；[Resilience4j Retry](https://resilience4j.readme.io/docs/retry) 的重试参数

# MustUnderstand

- [ImportJob.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJob.java) 第 6-42 行：owner/输入/状态/进度/attempt/error/时间戳/@Version（:19）与 start(:37)/succeed(:38)/fail(:39)/retry(:41)；start 自增 attempt，retry 只翻状态
- [ImportJobRetrySweeper.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java) 第 26-65 行：退避公式 :61-64、候选查询 :45、窗口过滤 :50-51、跨 bean 重投 :52-54；[ImportJobService.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobService.java) 第 17-36 行 afterCommit 与 id+owner 隔离
- [ImportJobWorker.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java) 第 12-27 行 @Async+独立事务+RECEIVED 门禁；[V3__async_import_jobs.sql](../../app/src/main/resources/db/migration/V3__async_import_jobs.sql) 全 15 行；[InfrastructureConfig.java](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) 第 22-33 行 import 池 2/4/100/CallerRunsPolicy（另有 generalTaskExecutor 通用池，避免挤占）
- 证据：ImportJobRetrySweeperTest 5 法（默认运行纯单元：窗口外重投/窗口内跳过/二次失败等待/空候选短路/退避封顶 10 分钟）；端到端流转见 M0FlowIntegrationTest:222
- 至少一次投递意味着处理器可能重复运行；“恰好一次”通常只能在局部事务/幂等效果层面近似；任务表是事实来源，线程池只是执行资源
- 外部源码索引（MustUnderstand）：[Spring Batch retry](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/retry-logic.html) 的 retry/skip 状态边界；[Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html) 的提交、发布、重复和顺序边界（项目真实实现见 event 包 OutboxRelay :46-68）
- 官方：[Spring Batch](https://docs.spring.io/spring-batch/reference/)、[Retry](https://resilience4j.readme.io/docs/retry)、[RabbitMQ Publisher Confirms](https://www.rabbitmq.com/docs/confirms)、[Kafka Consumer Commit](https://kafka.apache.org/documentation/#consumerconfigs_enable.auto.commit)
