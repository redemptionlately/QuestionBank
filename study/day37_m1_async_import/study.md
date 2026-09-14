# MustRemember

- 持久任务把 HTTP 接受与后台处理解耦；[ImportJobController.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobController.java) :20-26 是 202+Location 接受与 GET 轮询契约；[ImportJobService.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobService.java) :17 事务落库、:27 afterCommit 才触发 worker（绝不在请求线程里跑后台活）
- [ImportJobWorker.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java) :12-24：`@Async("importTaskExecutor")`+独立 `@Transactional`，start（attempt++、progress=10）→succeed/fail；异步池在 InfrastructureConfig:22-34（core2/max4/queue100/CallerRuns/关停等 30s）；@EnableAsync 在 QuestionBankApplication:8、@EnableScheduling 在 InfrastructureConfig:13
- **失败重试已实现**：[ImportJobRetrySweeper.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java) :26-63，默认 60s 扫 FAILED 且 attempt<3，退避 `min(600, 30<<(attempt-1))` = 30/60/120…封顶 600 秒，retry() 回 RECEIVED 并保留 error；配置键 app.import.retry.enabled（默认 true，application-test.yml:19 关闭）/sweep-ms=60000；5 个边界用例在 ImportJobRetrySweeperTest
- **仍未实现（设计态）**：leaseUntil 租约/心跳/多 worker 接管 PROCESSING 僵尸任务（现清扫器只扫 FAILED）、multipart 字节上传、PDF 文本抽取、candidate 审核、任务级死信；V3 真实列为 status/progress/error/attempt/entity_version(@Version)/created_at/updated_at，nextRunAt/leaseUntil/content_hash 是扩展设计
- 任务查询返回公开状态和错误；轮询简单可靠（前端 jobs.js 2s 间隔），SSE 是服务器到客户端单向流，WebSocket 支持双向通信
- worker 领取和业务写入要定义事务边界：领取提交后再处理，结果写入与状态推进保持可恢复；处理器必须幂等
- 异步接口响应是 `202 Accepted` 加 `Location: /api/import-jobs/{id}`；查询返回状态、进度、attempt、公开错误，而不是阻塞直到完成
- 外部源码索引（MustRemember）：[Spring `@Async`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) 的 executor/exception handler；[SseEmitter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html) 的事件接口

# MustUnderstand

- 现状状态机为 RECEIVED/PROCESSING/SUCCEEDED/FAILED + FAILED 退避重试回 RECEIVED（最多 3 次）；lease、心跳、多实例接管是扩展设计，不能把定时重试说成租约恢复，也不能把它说成未实现
- 导入成功不等于审核通过，审核通过不等于发布事务成功；每个阶段都有独立事实和失败状态（当前只实现到 SUCCEEDED，后续 candidate/审核/建 paper_version 均不存在）
- 外部源码索引（MustUnderstand）：[Spring Batch JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html) 的 job/step execution 状态持久化
- 官方：[Spring Batch](https://docs.spring.io/spring-batch/reference/)、[Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
