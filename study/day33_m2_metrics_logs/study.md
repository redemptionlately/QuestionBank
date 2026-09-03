# 必须会背会写

- [RequestMetrics.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/common/RequestMetrics.java) 第 6-23 行用三个 `AtomicLong` 记录请求总数、失败数和累计纳秒；`record` 封装同步调用，`request/failure/latency` 供过滤器组合使用
- [MetricsController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/common/MetricsController.java) 第 8-17 行通过 `GET /api/metrics` 导出 JSON；[RateLimitFilter.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/common/RateLimitFilter.java) 第 33-55 行说明请求计数、429/异常失败计数和 finally 计时的接入点；[InfrastructureConfig.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) 第 27-28 行注册单例指标 Bean

- Counter 只增不减，Gauge 表示瞬时值，Timer 记录耗时和分布，Histogram 记录可聚合桶，trace/span 表示跨组件调用关系
- HTTP 指标至少包含 route、method、status、请求数、错误率和 P95；数据库指标包含查询耗时、连接池、锁等待；异步指标包含队列深度和 oldest age
- 结构化日志使用固定字段和 JSON；`requestId` 关联一次请求，`traceId/spanId` 关联跨服务调用
- Micrometer 的 tag 必须低基数；用户 ID、题目 ID 等无限增长值不适合作为指标标签
- Micrometer 计时器代码形态是：
  ```java
  Timer timer = Timer.builder("practice.submit.latency")
      .tag("result", "success").register(registry);
  timer.record(() -> service.submit(user, sessionId, key));
  ```
- 外部源码索引（会背会写）：[Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) 的 `Timer.builder/register/record`；[SLF4J MDC](https://www.slf4j.org/manual.html) 的 requestId 字段

# 必须理解

- 累计耗时不能替代 p50/p95/p99 直方图；指标应区分 endpoint、状态码和错误原因，并防止把用户输入作为高基数标签。当前项目的指标是进程内原子计数基线，重启即丢失，且没有 route/status 维度、Prometheus registry 或分布式 trace

- 健康指标回答依赖是否可用，业务指标回答请求结果，审计日志回答谁在何时对哪个资源做了什么
- 高基数业务 ID 不应直接作为指标标签；人工接受率、系统成功率和模型准确率是不同指标
- 外部源码索引（必须理解）：[OpenTelemetry Java instrumentation](https://opentelemetry.io/docs/languages/java/instrumentation/) 的 trace/span/context 传播
- 官方：[Micrometer](https://micrometer.io/docs)、[OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
