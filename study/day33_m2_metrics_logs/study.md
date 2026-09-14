# MustRemember

- [RequestMetrics.java](../../app/src/main/java/com/allen/questionbank/common/RequestMetrics.java) 第 6-23 行用三个 `AtomicLong`（requests/failures/totalLatencyNanos，:7-9）记录请求总数、失败数和累计纳秒；泛型 `record(Supplier)` 在 :11，`request/failure/latency` 在 :18-23 供过滤器组合使用；Bean 注册于 [InfrastructureConfig.java](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) :58-59
- [MetricsController.java](../../app/src/main/java/com/allen/questionbank/common/MetricsController.java) :8-13 通过认证后的 `GET /api/metrics` 导出三计数 JSON；本地 [RateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java) :22-65（固定窗口，默认装配）与 Redis 版 [RedisRateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/redis/RedisRateLimitFilter.java) :25-55 在放行/429 路径接入同一 RequestMetrics
- [ServiceLatencyAspect.java](../../app/src/main/java/com/allen/questionbank/ops/ServiceLatencyAspect.java) :14-22：`@Aspect` 环绕所有 `..*Service.*(..)`，慢调用阈值 200ms（:18），只打 warn 日志、不注册 Timer；[DatabaseLatencyHealthIndicator.java](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java) :18-43：Actuator HealthIndicator，`SELECT 1` 探针、500ms 阈值判 UP/DOWN
- [RequestIdFilter.java](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) :22-41：透传/生成 `X-Request-Id`，MDC key 固定 `"requestId"`（:33），响应头回写（:34），finally remove 防线程复用串号（:41）；logback pattern `[%X{requestId:-}]` 在 logback-spring.xml:6
- 依赖 `io.micrometer:micrometer-registry-prometheus`（compile）；application.yml:28 `management.endpoints.web.exposure.include=health,info,metrics,prometheus`，:32 开启 health probes；`/actuator/health/**` 与 `/actuator/prometheus` 在 SecurityConfig:47-48 放行
- 已被测试验证：[OpsComponentsTest.java](../../app/src/test/java/com/allen/questionbank/OpsComponentsTest.java) 5 用例（健康指标 UP/DOWN、本地缓存命中计数、RequestMetrics 计数且不吞异常）、[M0OpsIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0OpsIntegrationTest.java) 3 用例（探针免 token、认证后 /api/metrics、Swagger 公开且响应带 X-Request-Id）

- Counter 只增不减，Gauge 表示瞬时值，Timer 记录耗时和分布，Histogram 记录可聚合桶，trace/span 表示跨组件调用关系
- HTTP 指标至少包含 route、method、status、请求数、错误率和 P95；数据库指标包含查询耗时、连接池、锁等待；异步指标包含队列深度和 oldest age
- 结构化日志使用固定字段和 JSON；`requestId` 关联一次请求（本项目已由 RequestIdFilter+MDC 落地），`traceId/spanId` 关联跨服务调用（仅 cloud/ 演示接入 OTel，app 单体未接入）
- Micrometer 的 tag 必须低基数；用户 ID、题目 ID、requestId 等无限增长值不适合作为指标标签，应进日志/trace
- Micrometer 计时器代码形态是：
  ```java
  Timer timer = Timer.builder("practice.submit.latency")
      .tag("result", "success").register(registry);
  timer.record(() -> service.submit(user, sessionId, key));
  ```
- 外部源码索引（MustRemember）：[Micrometer Timer](https://docs.micrometer.io/micrometer/reference/concepts/timers.html) 的 `Timer.builder/register/record`；[SLF4J MDC](https://www.slf4j.org/manual.html) 的 requestId 字段

# MustUnderstand

- 累计耗时不能替代 p50/p95/p99 直方图：自研 RequestMetrics 三 AtomicLong 只有全局总和、无样本分布、无 route/status 标签、重启归零；但项目已引入 micrometer-registry-prometheus，`/actuator/prometheus` 导出 JVM/Tomcat/HikariCP 等标准 Micrometer 指标——**业务自定义 submit Timer/Counter 仍未实现（设计态），ServiceLatencyAspect 只有超 200ms warn 日志而非直方图**，两条事实不能混淆

- 健康指标回答依赖是否可用（DatabaseLatencyHealthIndicator：SELECT 1 + 500ms 阈值），业务指标回答请求结果（/api/metrics 三计数），审计日志回答谁在何时对哪个资源做了什么；三者消费方与保留周期不同
- 高基数业务 ID 不应直接作为指标标签；人工接受率、系统成功率和模型准确率是不同指标
- 外部源码索引（MustUnderstand）：[OpenTelemetry Java instrumentation](https://opentelemetry.io/docs/languages/java/instrumentation/) 的 trace/span/context 传播
- 官方：[Micrometer](https://micrometer.io/docs)、[OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)
