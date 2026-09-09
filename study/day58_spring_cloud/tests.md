# Current（对应 cloud/ 拆分实现，全部可在本机复跑）

- 复跑 `./scripts/cloud-evidence.sh`，逐项核对 10 个 `[evidence]` 输出；对每一项能回答"它证明了什么、失败会长什么样"。
- 背写请求全链路：client -> 网关(RequestId) -> 验签(auth-service) -> 路由(lb://) -> 下游授权 -> Feign 快照 -> outbox/Kafka。画出来，并标注每一步失败时的返回码（401 / 429 / 503 / 409）。
- 用 `SHOW TABLES` 分别查 `qb_auth` / `qb_bank` / `qb_practice`，证明表集合互不相交；指出哪张表是"跨服务 JOIN 的替代品"（`practice_item` 固化的快照副本）。
- 打开 [AuthFilter.java](../../cloud/gateway-service/src/main/java/com/allen/cloud/gateway/AuthFilter.java) 第 44-87 行，独立写出"验签 + 透传身份 + 两类失败码"的过滤器骨架；解释为什么 `@LoadBalanced` 不能少。
- 改坏一次验证一次：把 practice 的 `spring.application.name` 改成别的，重启后观察 Eureka 注册表与 Feign 调用各自如何失败，并解释失败点在哪一层（注册 vs 发现）。
- 熔断实验：`./scripts/cloud-evidence.sh` 第 10 项杀 bank 后，手动 `curl http://127.0.0.1:8083/actuator/circuitbreakers` 看状态机；改小 `wait-duration-in-open-state` 重跑，观察恢复变快，并能说出 HALF_OPEN 放行的 3 个探测请求去哪了。
- 限流实验：登录后对 `GET /api/banks` 连发 10 次记录状态码序列，解释哪 5 个被放行、为什么第 6 个开始 429；把 `burstCapacity` 改成 8 重跑对比。

# External

- 解释拆分后"发布试卷"事务的边界变化：单体里一个 `@Transactional` 覆盖到 Kafka send 是错的，为什么 Outbox 是正确形态；画出 relay 崩溃在"已发送未标记"时点的重发时序。
- 设计配置中心（Config/Nacos）接入方案：哪些配置必须外置（数据库地址、token 密钥、限流阈值）、`/actuator/refresh` 的生效范围、以及为什么密钥仍应走环境变量而不是 git 仓库。
- 计算两层重试放大倍数：网关 retry=2 × Feign retry=3，峰值 100 QPS 时下游最多被打多少；给出"只在最内层 retry"的取舍理由。
- 说出把 gateway/auth/bank/practice 分别改成多实例部署时，哪些组件天然无状态、哪些需要引入共享存储（网关限流已用 Redis，session 已无状态化，还有哪里漏了）。
