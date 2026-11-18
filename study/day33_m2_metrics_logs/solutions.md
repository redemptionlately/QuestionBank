# Day33 M2 Metrics & Logs · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实代码：`common/RequestMetrics`、`common/MetricsController`、`common/RateLimitFilter`（本地固定窗口）、`redis/RedisRateLimitFilter`（Redis 限流过滤器）、`ops/ServiceLatencyAspect`、`ops/DatabaseLatencyHealthIndicator`、`common/RequestIdFilter`，以及 [application.yml](../../app/src/main/resources/application.yml) 的 actuator/prometheus 暴露配置。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（行号取自真实代码）：
- [common/RequestMetrics.java:6](../../app/src/main/java/com/allen/questionbank/common/RequestMetrics.java)：三个 `AtomicLong`（requests/failures/totalLatencyNanos，:7-9）；泛型 `record(Supplier)` 在 :11，`request/failure/latency` 计数方法在 :18-23。I/O：进程内自增、无 I/O；输出为 getter 快照。
- [common/MetricsController.java:8-9](../../app/src/main/java/com/allen/questionbank/common/MetricsController.java)：`GET /api/metrics`（:13）返回请求数/失败数/平均延迟 Map，需认证。
- [common/RateLimitFilter.java:22-24](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java)：本地固定窗口限流（`app.rate-limit.backend=local` 或缺省装配），doFilterInternal 在 :46，放行/429 都经 RequestMetrics 计数，Retry-After 在 :65。
- [redis/RedisRateLimitFilter.java:25-27](../../app/src/main/java/com/allen/questionbank/redis/RedisRateLimitFilter.java)：`app.rate-limit.backend=redis` 时的替代过滤器，超限同样计入 RequestMetrics。
- [ops/ServiceLatencyAspect.java:14-16](../../app/src/main/java/com/allen/questionbank/ops/ServiceLatencyAspect.java)：`@Aspect @Component`，切点 :21 拦截所有 `com.allen.questionbank..*Service.*(..)`，慢调用阈值 SLOW_CALL_THRESHOLD_MS=200（:18），measure :22 计时、超 200ms 打 warn、异常照常抛出。
- [ops/DatabaseLatencyHealthIndicator.java:18-19](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java)：实现 Actuator `HealthIndicator`，health() :30 执行 `SELECT 1`（:33），阈值 SLOW_QUERY_THRESHOLD_MS=500（:21），>500ms 或异常判 DOWN，details 带 probeQuery/latencyMs。
- [common/RequestIdFilter.java:22-24](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java)：每请求透传/生成 `X-Request-Id`，`MDC.put("requestId", …)`（:33，MDC key 固定字符串 `"requestId"`），响应头回写（:34），finally `MDC.remove`（:41）。

---

### Q2. 请求 /api/metrics，验证三字段并比较各类请求后的变化；解释重启归零。
真实返回：`{"requests":N,"failures":M,"totalLatencyNanos":T}`（三 AtomicLong 快照，平均值由 totalLatencyNanos/requests 现算）。
- 成功请求：requests+1，failures 不变，累计耗时增加；
- 鉴权失败（401）/越权（403）：发生在安全过滤链，**不经过** RateLimitFilter 的 failure 接入点，因此不计入 RequestMetrics.failures（本项目只在 429 与过滤器内异常路径调 `metrics.failure()`）；
- 429：本地 `RateLimitFilter` 与 Redis 版 `RedisRateLimitFilter` 都是先 `metrics.request()`，超限分支再 `metrics.failure()`，并返回 Retry-After；
- 业务异常：过滤器 finally 仍记录延迟；是否计 failure 以实际接入点为准（本地限流过滤器的异常路径计 failure，Controller 抛出的业务异常由 GlobalExceptionHandler 处理、不进这三个计数器）。
**重启归零原因**：这三个计数器是 JVM 内 `AtomicLong`，进程内状态、无持久化，重启即清零；它是轻量业务计数演示，长期容量/SLO 统计应走 Micrometer + `/actuator/prometheus`（本项目已引入 `micrometer-registry-prometheus`，见 Q3）。
另注：Actuator 侧另有 `GET /actuator/health`（含 DatabaseLatencyHealthIndicator 的 DB 延迟探针，liveness/readiness 探针免 token）与 `GET /actuator/prometheus`（Prometheus 文本格式，放行清单内免 token），[application.yml:28](../../app/src/main/resources/application.yml) 暴露 `health,info,metrics,prometheus`，:32 开启 health probes。

---

### Q3. 对照 record 与三组方法，说明为什么当前没有 P95、route、status 维度。
`RequestMetrics.record(Supplier)` 只把每次耗时**累加**到 totalLatencyNanos 一个总和，不保存每次耗时样本，因此只能算平均（总和/次数），**无法计算 P50/P95/P99**（分位数需要样本分布或直方图桶）；三个计数器也没有按 route、method、status 拆标签，是全局聚合值。
重构后的项目现状（如实区分）：
1. **已具备**：`micrometer-registry-prometheus` 为 compile 依赖，Actuator 自动注册 PrometheusMeterRegistry，`/actuator/prometheus` 导出 JVM、Tomcat、HTTP 等 Micrometer 标准指标，`/actuator/metrics/{name}` 可查单指标；
2. **已具备但非直方图**：`ServiceLatencyAspect` 对所有 *Service 方法计时，但只在 >200ms 时打一条 warn 日志，**没有注册 Timer、不产生分位数**；`DatabaseLatencyHealthIndicator` 只产出健康状态与本次探针耗时 details；
3. **仍未实现（设计态）**：submit 等**业务自定义** Timer/Counter（带 result/route 标签与 publishPercentiles）尚未写，属于本题 Q4/Q7 的设计练习，不能声称已存在。

---

### Q4. 设计 submit timer/counter。
- Counter：`practice.submit.total{result=success|idempotent_replay|conflict}`（只增）；
- Timer：`practice.submit.latency`，记录每次提交耗时分布；
- Gauge：当前 IN_PROGRESS 会话数（瞬时值）；
- 按 result/route 低基数标签拆分，区分成功、幂等重放、409 冲突。
> 现状锚点：提交入口 [practice/PracticeController.java:49-50](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java)（强制 `Idempotency-Key` 头），核心逻辑 `practice/PracticeService.submit`（:56-57，幂等+判分+FOR UPDATE）；`app.lock.backend=redis` 时外层还有 `practice/RedisGuardedPracticeSubmitter`（锁 TTL 10s、抢锁失败 409 SUBMIT_IN_PROGRESS），这些都是 result 标签取值的真实来源。**当前代码未注册上述 Meter，属待实现设计。**

---

### Q5. 写结构化日志。
项目已具备 requestId 链路：`RequestIdFilter` 优先透传入站 `X-Request-Id` 头、缺失则 UUID，并写入 MDC（key 固定 `"requestId"`）；[logback-spring.xml:6](../../app/src/main/resources/logback-spring.xml) 的 pattern 含 `[%X{requestId:-}]`，无值显示 `-`；`ErrorResponse` record 带 requestId 字段，由 GlobalExceptionHandler 回填。业务日志写法：
```java
// requestId 已由 RequestIdFilter 放入 MDC，无需手动 put；直接打结构化键值
log.info("practice.submit sessionId={} result={} totalScore={} latencyMs={}",
         sessionId, result, score, elapsedMs);
// JSON appender 下输出：
// {"ts":"...","level":"INFO","requestId":"...","event":"practice.submit",
//  "sessionId":12,"result":"success","totalScore":8,"latencyMs":12}
```
跨服务用 traceId/spanId 传播（app 单体未接入分布式 trace；`cloud/` 四服务经 micrometer-tracing-bridge-otel + OTLP/HTTP 导出，属另一套独立演示，不与 app 共享代码）；禁止打印密码、token、答案隐私正文。

---

### Q6. 区分 Counter/Gauge/Timer/Histogram/trace，并列出低基数标签。
| 类型 | 含义 | 例子 |
|---|---|---|
| Counter | 只增不减的累计计数 | 请求总数、错误总数 |
| Gauge | 可增可减的瞬时值 | 线程池活跃数、队列深度、在线会话 |
| Timer | 计时 + 次数，记录耗时分布 | 接口延迟 |
| Histogram | 可聚合的分布桶 | 请求大小、耗时分位 |
| trace/span | 跨组件一次调用的链路与层级 | traceId 串起多个 span |
**低基数 tag**（取值有限、可枚举）：route（路由模板，不含具体 id）、method（GET/POST…）、status（200/409…）、result（success/fail）、clientType。HTTP 指标至少含 route/method/status/请求数/错误率/P95；DB 指标含查询耗时、连接池占用、锁等待；异步指标含队列深度、最老任务年龄（oldest age）。
本项目可直接观察的现成对应物：HikariCP 连接池 Gauge、JVM 内存/线程 Gauge、Tomcat 会话与请求 Timer 均由 Actuator 自动注册到 `/actuator/prometheus`；自研 RequestMetrics 三计数不在 Micrometer 体系内，只经 `/api/metrics` 输出。

---

### Q7. 写 Micrometer Timer 代码与 submit 命名；指出三个不该做 tag 的高基数字段。
```java
Timer timer = Timer.builder("practice.submit.latency")
        .tag("result", "success")          // 低基数
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry);
timer.record(() -> practiceService.submit(user, sessionId, key));
```
命名遵循 `<领域>.<动作>.<度量>`，如 `practice.submit.latency`、`practice.submit.total`。
**三个不应作为 tag 的高基数字段**：userId、sessionId/题目 id（每个用户/资源一个值，标签无限膨胀）、requestId（每请求唯一）。这类标识放日志/trace，不放进指标标签。
> 项目内可参照的真实计时风格：`ServiceLatencyAspect.measure`（:22）用 `System.nanoTime()` 环绕计时、异常照常抛出——把它从"打 warn 日志"升级为"Timer.record + 慢日志"就是本题的标准落地形态。

---

## External

### E1. 制造高延迟比较平均与 P95。
人为让 5% 请求 sleep 500ms、其余 10ms：平均被少量慢请求轻微抬高，但 P95 直接落在慢请求区间，能反映尾部体验；只看平均会掩盖长尾。结论：SLO 用 P95/P99 + 成功率，均值仅作辅助。项目内的开环压测器 [tools/loadgen/Loadgen.java](../../tools/loadgen/Loadgen.java) 报告 P50/P95/P99/P999（`ceil(pct/100*n)-1` 索引），可直接用来对比均值与尾部。

### E2. 避免高基数 tag。
把用户 id、题目 id、requestId 从标签中移除，改用有限枚举标签；需要定位单个请求时用日志（requestId，本项目由 RequestIdFilter/MDC 落地）或链路追踪（traceId）。高基数 tag 会让时间序列数量爆炸、内存与抓取成本飙升，Prometheus 甚至出现 cardinality 问题。
