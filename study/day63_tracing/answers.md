# Day 63 · 快问快答（闭卷自测，先答后翻 solutions.md）

1. traceparent 头四个字段分别是什么？下游 SERVER span 的 parentSpanId 等于上游哪个 span 的 id？
2. micrometer-tracing-bridge-otel 和 opentelemetry-exporter-otlp 各干什么？
3. `management.tracing.sampling.probability` 默认值？为什么证据环境设 1.0？
4. 日志里 `[bank-service,<traceId>,<spanId>]` 的两个值从哪来（什么机制放进 MDC）？
5. Feign 跨服务传播缺哪个依赖时断裂？
6. 注入的 `WebClient.Builder` 为什么 LB 过滤器在而 observation 不在？（同名覆盖，说全）
7. 手动再挂 DeferringLoadBalancerExchangeFilterFunction 会发生什么？503 的报错原文是什么？
8. 服务首次注册 eureka 的默认最长延迟？gateway LB 缓存默认刷新周期？两者叠加的症状？
9. 为什么断言 span 导出齐备要轮询而不是固定 sleep？轮询信号选什么？
10. 为什么日志-链路互查锚点不能抓任意带 trace 的日志行？本项目选哪一行？
11. collector 停了业务会怎样？这证明可观测性什么性质？
12. 采样率 0.1 时，"三服务同 trace"断言为什么容易失败？生产怎么补？
13. 画出锚点 B 的 7 span 调用树（四个服务名 + 两个 CLIENT span 的角色）。
14. AuthFilter 修好后 trace 为什么仍然断过一次（run4 之前）？最后改了哪一行代码？
