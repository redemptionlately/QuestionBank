# Day33 M2 Metrics & Logs · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `RequestMetrics`、`MetricsController`、`RateLimitFilter` 接入点。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`RequestMetrics` 6-24 行三个 AtomicLong 与 record/request/failure/latency；`MetricsController` 8-17 行 `GET /api/metrics`；`RateLimitFilter` 33-56 行计数/失败/finally 计时。

---

### Q2. 请求 /api/metrics，验证三字段并比较各类请求后的变化；解释重启归零。
真实返回：`{"requests":N,"failures":M,"totalLatencyNanos":T}`。
- 成功请求：requests+1，failures 不变，累计耗时增加；
- 鉴权失败（401）/越权（403）：过滤器/安全链计入请求，是否计 failure 取决于接入点（本项目在 429 与抛异常时 `metrics.failure()`）；
- 429：`metrics.request()` 后超限分支 `metrics.failure()`；
- 业务异常：filter 的 catch 分支 `failure()`。
**重启归零原因**：三个计数器是 JVM 内 `AtomicLong`，进程内状态、无持久化、无 Prometheus registry，重启即清零，因此它只是基线演示，不能用于长期容量/SLO 统计。

---

### Q3. 对照 record 与三组方法，说明为什么当前没有 P95、route、status 维度。
`record(Supplier)` 只把每次耗时**累加**到一个总和（totalLatencyNanos），没有保存每次耗时样本，因此只能算平均（总和/次数），**无法计算 P50/P95/P99**（分位数需要保留分布或直方图桶）；计数器也没有按 route、method、status 拆标签，是全局聚合值。要获得分位数与维度，需引入 Micrometer Timer（带直方图/服务端分位）+ Prometheus registry。

---

### Q4. 设计 submit timer/counter。
- Counter：`practice.submit.total{result=success|idempotent_replay|conflict}`（只增）；
- Timer：`practice.submit.latency`，记录每次提交耗时分布；
- Gauge：当前 IN_PROGRESS 会话数（瞬时值）；
- 按 result/route 低基数标签拆分，区分成功、幂等重放、409 冲突。

---

### Q5. 写结构化日志。
用 JSON/固定字段、一行一条事件，通过 MDC 注入 requestId：
```java
MDC.put("requestId", requestId);
log.info("practice.submit sessionId={} result={} totalScore={} latencyMs={}",
         sessionId, result, score, elapsedMs);
// 输出（JSON appender）：
// {"ts":"...","level":"INFO","requestId":"...","event":"practice.submit",
//  "sessionId":12,"result":"success","totalScore":8,"latencyMs":12}
```
跨服务用 traceId/spanId 传播；禁止打印密码、token、答案隐私正文。

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

---

## External

### E1. 制造高延迟比较平均与 P95。
人为让 5% 请求 sleep 500ms、其余 10ms：平均被少量慢请求轻微抬高，但 P95 直接落在慢请求区间，能反映尾部体验；只看平均会掩盖长尾。结论：SLO 用 P95/P99 + 成功率，均值仅作辅助。

### E2. 避免高基数 tag。
把用户 id、题目 id、requestId 从标签中移除，改用有限枚举标签；需要定位单个请求时用日志（requestId）或链路追踪（traceId）。高基数 tag 会让时间序列数量爆炸、内存与抓取成本飙升，Prometheus 甚至出现 cardinality 问题。
