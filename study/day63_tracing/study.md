# Day 63 · 链路追踪：Micrometer Tracing + OpenTelemetry 真机取证

# MustRemember

- 分布式追踪的三层 ID：**traceId**（一次请求全局唯一，32 hex）、**spanId**（一个工作单元，16 hex）、**parentSpanId**（把 span 连成树）。跨进程传播靠 **W3C traceparent 头**：`00-<traceId>-<parentSpanId>-<flags>`——上游的 spanId 到下游变成 parentSpanId，trace 树就是这样跨服务长出来的。
- **Micrometer Tracing 是门面，OpenTelemetry 是实现**：`micrometer-tracing-bridge-otel` 把 Observation API 落到 OTel SDK；`opentelemetry-exporter-otlp` 把 span 以 **OTLP/HTTP** 发给接收端。版本全部由 Boot 3.4 BOM 管，不写版本号。
- Boot 配置四件套缺一不可：①两个依赖 ②`management.tracing.sampling.probability: 1.0`（**默认 0.1 会随机丢 90% span**，证据环境必须全采样）③`management.otlp.tracing.endpoint: http://127.0.0.1:4318/v1/traces` + `transport: http` ④`logging.pattern.level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"` 把 MDC 打进每行日志。
- **Feign 跨服务传播要显式加 `feign-micrometer`**：缺它 OpenFeign 调用既不产生 CLIENT span 也不透传 traceparent，trace 在 Feign 边界断裂（本项目实测）。
- **@LoadBalanced 的 WebClient.Builder 会被 Spring Cloud 用来同名覆盖 Boot 的 prototype builder**（bean 定义覆盖，Boot 默认允许）：注入到手的 builder **LB 过滤器在**（服务名能解析），但 `ObservationWebClientCustomizer` 从未作用于它——出站既不开 CLIENT span 也不带 traceparent，下游 span 全成孤立根 trace。修法是**链式补 `observationRegistry`**：`builder.observationRegistry(registry).build()`。
- **绝对不要再手动挂 `DeferringLoadBalancerExchangeFilterFunction`**：builder 上已带（同名覆盖的结果），再挂同一个 = **双重解析**——第一遍 auth-service→实例 IP，第二遍把实例 IP 当服务名找 `No servers available for service: 192.168.1.103` → 503（实测踩过）。
- Eureka 时序竞争：服务**首次注册默认最长延迟 40s**（`eureka.client.initial-instance-info-replication-interval-seconds`），gateway 的 **LB 缓存刷新默认 35s**（`spring.cloud.loadbalancer.cache.refresh-interval`）——加起来赶不上"服务刚就绪就发起跨服务调用"的时刻，症状是网关 503 `No servers available for service: practice-service`。解法：两边都调快 + 脚本轮询 eureka apps API 确认注册再发请求。
- **span 导出是批量异步的**（BatchSpanProcessor 默认 5s 一批）：断言"trace 齐备"必须**轮询到信号出现**（如"三个服务出现在同一条 trace"），固定 sleep 会撞上批次未齐的假阴性（首跑 18s 只解析到 121/310 个 span）。
- 日志-链路互查的锚点必须选**请求路径的日志行**：`@Scheduled` 调度线程的日志同样带 `[service,traceId,spanId]` 前缀（调度任务也被 Observation 包住），任意抓第一行会把后台调度的 trace 当成请求 trace（实测踩过）。本项目选 `internalSnapshot` 里的 `[snapshot]` INFO 行——只有 Feign 真实到达 bank 才会打印。
- 接收端用 `otelcol-contrib`：OTLP http :4318 / grpc :4317+1 收全部 span，**debug exporter `verbosity: detailed` 把每个 span 原样打印到 stdout 落盘**——证据文化要求解析对象就是落盘原件，不依赖任何第三方 UI。

# MustUnderstand

- 一条"完整锚点 trace"长什么样（实测 7 span 4 服务）：gateway SERVER（入口）→ gateway CLIENT（验签调用 auth）→ auth SERVER（verify）；gateway CLIENT（路由 practice）→ practice SERVER → practice CLIENT（Feign 拉快照）→ bank SERVER（/internal）。**同一 traceId，parent 链跨三个进程闭合**——这就是"分布式追踪"四个字的全部。
- 谁负责传播：入口（gateway SERVER）由 Reactor Netty + gateway observation 产生；**出站 WebClient/Feign 的 CLIENT span 由 ObservationWebClientCustomizer / feign-micrometer 注入**；下游 SERVER span 由 Boot web observation 产生，parent 取自请求头里的 traceparent。任何一环缺定制，链条就在那一跳断掉。
- 为什么链路断裂只发生在"某一跳"：传播是**逐跳的本地行为**——gateway→auth 断不影响 gateway→bank 通。排障时按跳定位：看 collector 里 SERVER span 的 parent 是否解析得到，解析不到就是上游那一跳没带 traceparent。
- 错误路径也被追踪：伪造 token 的 401 在 collector 里是带 `http.response.status_code=401` 的 gateway span——网关在验签失败时自己结束响应（不路由），span 由 gateway observation 记录。追踪的价值正在于"失败请求也留痕"。
- 采样与成本的权衡：probability=1.0 是证据环境的正确选择；生产按流量选 0.01-0.1 或尾部采样。采样丢的是**整条 trace**（首 span 决定），不是随机丢单个 span——所以低采样下"三服务同 trace"的断言成功率按采样率的三次方衰减。
- 导出失败不影响业务：OTLP 发送是后台批量异步的，collector 不在线时 span 在内存队列攒着、超限丢弃，业务请求零感知——这是"可观测性是旁路"的具体含义。
- otelcol 的角色：它是**厂商中立的收集层**（receiver → pipeline → exporter），后端可以换 Jaeger/Tempo/云厂商而不动应用配置。本项目用 debug exporter 是证据需要：原始 span 文本落盘，grep/正则即可复核，不引入黑盒 UI 依赖。
- 外部索引（MustUnderstand）：[OpenTelemetry OTLP 规范](https://opentelemetry.io/docs/specs/otlp/)、[Micrometer Tracing 概念](https://micrometer.io/docs/tracing)、[W3C Trace Context](https://www.w3.org/TR/trace-context/)
