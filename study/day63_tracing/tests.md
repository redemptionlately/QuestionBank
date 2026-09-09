# Day 63 · 验收任务

## 上半部分：背写验收（合上资料独立写出）

1. 写出 W3C traceparent 头的完整格式，并说明 traceId/parentSpanId 在"网关 → practice → bank"两跳传播中各自如何变化。
2. 写出本项目链路追踪的两个 Maven 依赖坐标（groupId:artifactId），并说明各自角色（谁把 Observation API 落到 OTel SDK、谁负责导出）。
3. 默写 Boot application.yml 里链路追踪的四件配置（采样率、OTLP 端点、传输方式、日志 pattern），并说出采样率默认值是什么、为什么证据环境必须改成 1.0。
4. 默写"日志-链路互查"的日志 pattern 行，并解释 `%X{traceId:-}` 从哪来。
5. 写出 AuthFilter 修复后的构造器代码，并回答：为什么注入的 `WebClient.Builder` 上 LB 过滤器已经在、observation 却不在？
6. 写出双重解析故障的完整因果链：手动挂 DeferringLoadBalancerExchangeFilterFunction 之后，两次 LB 解析分别把什么当服务名、最终症状是什么。

## 下半部分：变式 / 故障注入 / 面试追问（全部可在本机复跑）

7. 复跑 `./scripts/tracing-evidence.sh`，逐项核对 5 条 `[evidence]` 输出；对每一项回答"它证明了什么、如果它失败最可能是什么原因"。
8. 打开 `output/tracing_collector.log`，找到覆盖 4 个服务的那条 trace（用 `scripts/parse_tracing.py` 输出的 traceId），手工按 Parent ID 画出 7 个 span 的树，标出哪两个 CLIENT span 分别对应"验签调用"与"Feign 调用"。
9. 采样率实验：把四个服务 `management.tracing.sampling.probability` 临时改回 0.1，重跑证据，观察"跨 3 服务同一 trace"断言是否还能稳定通过——解释为什么低采样下跨服务 trace 的命中率按采样率的幂次衰减。
10. 故障注入：把 collector 停掉（`./scripts/tracing-evidence.sh stop` 只停 collector 也行），再打几条请求，验证业务接口仍然 200——说出这证明了可观测性的什么性质。
11. 时序实验：把 `eureka.client.initial-instance-info-replication-interval-seconds` 和 `spring.cloud.loadbalancer.cache.refresh-interval` 恢复默认（40s/35s），立刻重跑证据，观察锚点 B 是否偶发 503——复现后解释竞争窗口怎么算（注册延迟 + 缓存刷新）。
12. 面试追问演练：
    - "你们怎么做跨服务排障？"——要求答案以"一条 trace 里的 7 个 span"为起点，而不是背概念。
    - "验签 span 为什么曾是孤立的？"——要求说出同名 bean 覆盖 + ObservationWebClientCustomizer 未生效 + 修法三层。
    - "为什么不用固定 sleep 等 span 导出？"——要求说出 BatchSpanProcessor 批量语义与轮询信号设计。
