# Day58 Spring Cloud · 题目与标准解答（Solutions）

> 本拆分已在 `cloud/` 落地并可复跑（`./scripts/cloud-evidence.sh`，10 项证据）。
> 每题先给标准解答，再给"本项目实锤"——面试时两者都要能说。

## Current

### Q1. 为题库业务设计网关、服务发现、超时、重试、熔断、降级。

**标准解答**：按业务边界拆 auth（认证）/ bank（出题发布）/ practice（练习判分）；Eureka 注册发现，Gateway 统一入口做路由+鉴权+限流但不写业务规则；Feign 声明式调用并显式设超时；Resilience4j 熔断 `CLOSED→OPEN→HALF_OPEN`；降级分"快速失败"与"返回兜底数据"两类，按业务语义选。

**本项目实锤**：
- 拆分边界：[cloud/pom.xml](../../cloud/pom.xml) 六模块；每服务独立 schema，`SHOW TABLES` 可验证表集合互不相交；
- 网关只验签+透传身份：[AuthFilter.java:70-87](../../cloud/gateway-service/src/main/java/com/allen/cloud/gateway/AuthFilter.java)（服务名调用 auth 验签 → 塞 `X-User-Id` → 401/503 分流）；
- Feign 超时 2s/3s + 熔断参数：[practice application.yml:38-48](../../cloud/practice-service/src/main/resources/application.yml)；
- 降级的诚实版本：建会话无有意义兜底数据 → **快速失败 503**（[BankSnapshotGateway.java:43-47](../../cloud/practice-service/src/main/java/com/allen/cloud/practice/BankSnapshotGateway.java)），而不是返回空卷假装成功；
- 重试只放在 outbox relay（2s 轮询），调用链上不叠 retry，避免流量乘法。

### Q2. 写出 OpenFeign 调用的错误映射和幂等条件，指出哪些异常不能重试。

**标准解答**：4xx（除 429）是客户端错误直接失败；429/503/连接拒绝是瞬态，可有限退避重试；**非幂等写不自动重试**；401/403 与业务校验 400 永不重试；重试前先确认接口幂等（GET 或带幂等键的写）。

**本项目实锤**：
- 幂等条件落在**建会话**上：[PracticeService.java:45](../../cloud/practice-service/src/main/java/com/allen/cloud/practice/PracticeService.java) 先按 `clientToken` 查（唯一键 `uk_practice_session_token` 兜底），命中直接返回旧会话——所以"重试安全"；
- 且**幂等查询在 Feign 调用之前**：顺序反了，每次重试都会先白白打一次 bank，重试放大下游压力；
- 证据输出：`[evidence] 5.Feign+幂等：两次同 clientToken 建会话 -> sessionId=X 与 X（应相等）`。

### Q3. 画出一次请求经过网关、服务发现、负载均衡、下游服务和数据库的链路。

```
client → gateway:8080
          ├─ RequestIdFilter：无 X-Request-Id 则生成 UUID（请求+响应头都写，三服务日志靠它串联）
          ├─ AuthFilter：lb:// 解析 auth-service → /internal/token/verify 验签
          │    ├─ 401：token 无效 → 401 TOKEN_INVALID
          │    └─ 连不上 auth → 503 AUTH_SERVICE_DOWN（门禁坏了只能关门）
          ├─ 路由匹配 Path=/api/banks|practice|auth → lb://下游（先查 Eureka 实例表，LB 选一个）
          ├─ bank 路由：Redis 令牌桶限流（2/s、桶 5）→ 超额 429
          └→ 下游服务校验 X-User-Id/X-User-Role（服务自己是最后一道防线）→ 本库事务
practice 建会话分支：Feign → bank /internal/papers/{id}/snapshot → 快照固化进 qb_practice
bank 发布分支：paper_version + paper_item + outbox_event 同一事务 → relay 异步投 Kafka
```

**本项目实锤**：证据第 1-4 项逐段验证了这条链；`[evidence] 3.网关统一鉴权` 一行同时给出"无 token 401 / 伪造 token 401 / 绕过网关直连被拒"三个数字。

---

## External

### E1. 拆分后"发布试卷"的事务边界如何变化。

**标准解答**：单体里一个 `@Transactional` 全覆盖；拆分后强一致只能留在**服务内**，跨服务走 Saga 或 Outbox + 事件最终一致，发布可能变成异步状态机。

**本项目实锤**：本拆分把"创建版本 + 固化题目 + 写事件"完整留在 bank-service **一个本地事务**里（这正是数据所有权划分的红利——不是所有拆分都会打散事务），跨服务只剩"至少一次"的事件传播：[BankService.java:69-83](../../cloud/bank-service/src/main/java/com/allen/cloud/bank/BankService.java) + [OutboxRelay.java:43-55](../../cloud/bank-service/src/main/java/com/allen/cloud/bank/OutboxRelay.java)。并发发布的兜底不是应用层判断而是 `(bank_id, version)` 唯一约束，撞键转 409（第 70-72 行）。

### E2. 配置中心回滚、实例摘除、下游雪崩恢复路径。

**标准解答**：配置带版本可回滚、灰度放量、敏感字段不进 git；实例摘除 = 健康检查失败 → 注册中心标记下线 → LB 停止选入；雪崩恢复 = 熔断 OPEN 保护 → 冷却 → HALF_OPEN 少量探测 → 阶梯放量。

**本项目实锤 + 诚实边界**：熔断恢复已实测（证据第 10 项：OPEN → 等 10s → HALF_OPEN 放 3 探测 → CLOSED）；Eureka 剔除参数 `eviction-interval-timer-in-ms: 4000` 且演示环境**关掉了自我保护**（生产必须打开——网络抖动时防止健康实例被全量剔除）。**配置中心未接入**，当前配置在各服务 yml + 环境变量，这是已声明的未验证项，不写进简历能力清单。

### E3. 计算两层重试在峰值流量下的放大倍数并设置上限。

**标准解答**：外层 3 次 × 内层 2 次 = 最坏 6 倍；下游已过载时 6 倍流量加速雪崩。对策：只在最内层或最外层之一 retry、全局重试预算、退避加抖动、熔断在预算耗尽前打开。

**本项目实锤**：整条链路只保留一处重试——outbox relay 的"下轮再试"（Kafka 侧），HTTP 调用链零 retry；证据第 9 项的限流本身就是"过载时快速拒绝"的第一道闸。
