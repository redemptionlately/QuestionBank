# Day 63 · 解答与源码索引

## 背写验收解答

1. **traceparent**：`00-<traceId>-<parentSpanId>-<flags>`，如 `00-07d597b7ec943183f67af157772c0807-3b9b69177113e848-01`。两跳传播：网关自己生成 traceId 与入口 spanId → 调 practice 时把 traceId + gateway CLIENT spanId 写进头 → practice 的 SERVER span 以该 spanId 为 parentSpanId、自身产生新 spanId → practice 调 bank 时再写 traceId + practice CLIENT spanId → bank SERVER 又以它为 parent。**traceId 全程不变，spanId 每跳换新，parent 指向上游 CLIENT**。
2. `io.micrometer:micrometer-tracing-bridge-otel`（把 Observation/Micrometer API 落到 OpenTelemetry SDK）+ `io.opentelemetry:opentelemetry-exporter-otlp`（把 SDK 的 span 以 OTLP/HTTP 导出）。Feign 链路另需 `io.github.openfeign:feign-micrometer`。版本全由 Boot BOM 管理。
3. 四件配置（见 `cloud/*/src/main/resources/application.yml` 的 management 节）：
   ```yaml
   management:
     tracing:
       sampling:
         probability: 1.0
     otlp:
       tracing:
         endpoint: http://127.0.0.1:4318/v1/traces
         transport: http
   logging:
     pattern:
       level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
   ```
   默认采样率 **0.1**——随机丢 90% 的 trace，证据要求每条请求可追溯，必须 1.0。
4. `%X{traceId:-}` 从 **SLF4J MDC** 取：micrometer-tracing 的 W3C 上下文把当前 traceId/spanId 放进 MDC，pattern 里的占位符逐行展开；`:-` 是"缺省为空"（非请求线程如启动期没有 trace 时输出空）。
5. 构造器（`AuthFilter.java:66-68`）：
   ```java
   public AuthFilter(WebClient.Builder builder, ObservationRegistry observationRegistry) {
       this.authClient = builder.observationRegistry(observationRegistry).build();
   }
   ```
   原因：Spring Cloud 用**同名**（`webClientBuilder`）的 @LoadBalanced builder 覆盖了 Boot 的 prototype builder（bean 定义覆盖，Boot 默认允许）。注入到手的 builder 定义上带 @LoadBalanced → `LoadBalancerWebClientBuilderBeanPostProcessor` 给它挂了 LB 过滤器；但 Boot 的 `ObservationWebClientCustomizer` 只在 Boot 那个 bean 定义创建时应用——定义被覆盖后**从未执行**。所以 LB 在、observation 不在，链式补 registry 即可。
6. 双重解析因果链：Spring 预置 builder 已带 DeferringLoadBalancerExchangeFilterFunction → 手动 `builder.filter(同一个过滤器)` 后链条里有两个 → 请求 `http://auth-service/...` 第一遍把 `auth-service` 解析成 `http://192.168.1.103:8081` → 第二遍过滤器取 `request.url().getHost()` 当 serviceId，拿 `192.168.1.103` 去注册表找实例 → `No servers available for service: 192.168.1.103` → 网关 onErrorResume 落到 503 AUTH_SERVICE_DOWN。DEBUG 日志能看到同一请求 20ms 内两遍过 LB。

## 变式/追问解答要点

7. 5 条证据语义：①跨 3 服务同 traceId = 请求级关联成立；②parent 链在单 trace 内闭合（根唯一、无断链）= traceparent 真实透传；③practice CLIENT 存在且 bank SERVER.parent 等于它 = Feign 传播成立；④401 的 gateway span = 错误路径也被追踪；⑤bank 日志行 traceId/spanId 在 collector 中反查命中 = 日志与 trace 可互查。失败长相：①失败→看各 trace 的服务分布（断裂在哪一跳）；②失败→trace 根多于 1（孤立根）或 parent 解析不到；③失败→feign-micrometer 缺失或 B 锚点没到 practice；④失败→伪造 token 请求没经过 gateway observation；⑤失败→日志 pattern 没生效或锚点行抓错（如抓到调度线程日志）。
8. 7 span 树（终轮实测 trace `db43c9e7bca798903c5d9dd2f651ad46`）：
   ```text
   [gateway-service|Server] http post            ← 学生 POST /api/practice/sessions
     [gateway-service|Client] HTTP POST          ← 验签调用 auth
       [auth-service|Server] http post /internal/token/verify
     [gateway-service|Client] HTTP POST          ← 路由 practice
       [practice-service|Server] http post /api/practice/sessions
         [practice-service|Client] HTTP POST     ← Feign 拉快照
           [bank-service|Server] http get /internal/papers/{id}/snapshot
   ```
9. 采样 0.1 时每条 trace 以 10% 概率被保留；"跨 3 服务"要求被抽中的恰好是那条跨服务 trace——锚点请求总量少（几条），命中率太低，断言可能空手而归。生产低采样要配合"按 trace 特征保留"（尾部采样/错误优先），不能指望头采样兜住关键链路。
10. collector 停机后接口仍 200：OTLP 导出在 BatchSpanProcessor 的后台线程批量进行，失败仅在内存队列重试/丢弃，请求线程从不等待导出——可观测性必须是旁路，不能成为故障域。
11. 默认 40s 注册 + 35s 缓存刷新下，"practice 就绪 → gateway 首次路由它"的窗口内 LB 可能拿不到实例（run3 实测 503 `No servers available for service: practice-service`）。竞争窗口 ≈ 注册延迟（0~40s）− gateway 启动到锚点 B 的间隔 + 0~35s 缓存陈旧。修法是两侧调快 + 脚本轮询 eureka apps API 到注册出现再留一个刷新周期。
12. 面试要点见 tests.md 第 12 条，答题骨架分别是"一条 trace 的故事 / 三层根因 / 信号轮询"，不要背"W3C 规范"这类空词。

## 源码索引

- `cloud/gateway-service/src/main/java/com/allen/cloud/gateway/AuthFilter.java:49-68` → 背写同名覆盖坑与修法；第 2 条 PROTECTED_PREFIXES 决定哪些路径走验签。
- `cloud/practice-service/src/main/java/com/allen/cloud/practice/BankSnapshotGateway.java:37-48` → Feign + 熔断快速失败的边界（降级 = 明确失败，不是假成功）。
- `cloud/bank-service/src/main/java/com/allen/cloud/bank/BankController.java:81-89` → `[snapshot]` 日志锚点为什么必须在请求路径打。
- `scripts/tracing-evidence.sh` → run 模式全流程：清孤儿 → 基础设施 → collector → 编译 → 5 服务 → 3 锚点 → 轮询导出齐备 → python 断言。
- `scripts/parse_tracing.py` → 容错正则解析原始 span 文本、5 项断言、调用树渲染；学"证据解析器怎么写得可复核"。
- `scripts/otelcol-config.yaml` → OTLP 双协议 receiver + debug exporter 配置。
- `output/tracing_collector.log` / `output/tracing_evidence_console.log` → 全部原始证据。
