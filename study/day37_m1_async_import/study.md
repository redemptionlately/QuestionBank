# 必须会背会写

- 持久任务把 HTTP 接受与后台处理解耦；扩展任务表可保存 `status/progress/error/attempt/nextRunAt/leaseUntil`
- HTTP 请求只创建任务并返回 task ID；worker 从持久任务领取文件、文本和候选，不占用 Web 请求线程。当前 M0 已有确定性导入 worker，但尚未接真实文件解析
- 任务查询返回公开状态和错误；轮询简单可靠，SSE 是服务器到客户端单向流，WebSocket 支持双向通信
- worker 领取和业务写入要定义事务边界：领取提交后再处理，结果写入与状态推进保持可恢复
- 异步接口响应通常是 `202 Accepted` 加 `Location: /api/import-jobs/{id}`；查询资源返回状态、进度和公开错误，而不是阻塞直到 PDF 完成
- 项目基线索引：[ImportJobController.java](../../src/main/java/com/allen/questionbank/importjob/ImportJobController.java) 第 20-33 行的 202/Location/轮询契约；[ImportJobService.java](../../src/main/java/com/allen/questionbank/importjob/ImportJobService.java) 第 17-29 行的持久化与 after-commit 调度；[ImportJobWorker.java](../../src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java) 第 12-27 行的异步独立事务
- 外部源码索引（会背会写）：[Spring `@Async`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) 的 executor/exception handler；[SseEmitter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html) 的事件接口

# 必须理解

- 当前 worker 只有 `RECEIVED/PROCESSING/SUCCEEDED/FAILED` 状态和一次处理尝试；异步任务的 lease、心跳、重试和死信是 M1/M2 扩展，不能把学习设计写成项目已实现
- 导入成功不等于审核通过，审核通过不等于发布事务成功；每个阶段都有独立事实和失败状态
- 外部源码索引（必须理解）：[Spring Batch JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring-repository.html) 的 job/step execution 状态持久化
- 官方：[Spring Batch](https://docs.spring.io/spring-batch/reference/)、[Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
