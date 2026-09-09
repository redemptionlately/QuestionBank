# MustRemember

## 拆分决策（先回答"为什么拆"，再回答"怎么拆"）

- 拆分依据是**业务边界 + 数据所有权**，不是类数量：认证、题库发布、练习判分是三条变更节奏不同的业务线，各自拥有独立 schema（`qb_auth` / `qb_bank` / `qb_practice`），表集合互不相交。
- 数据所有权的物理证据是 `SHOW TABLES`：任何一张表只属于一个服务。跨服务取数只能走接口（同步 Feign）或事件（异步 Kafka），**跨服务 JOIN 是拆分失败的第一信号**。
- 单体模块化与微服务是取舍不是升级：拆出去换来独立部署与技术异构，代价是分布式事务、链路追踪、版本兼容。M0 单体的价值在事务内事实清晰，cloud 拆分演示的是边界划清之后事务如何跨服务表达。
- 试卷快照（`PaperSnapshot`）是"数据所有权"落地的关键设计：practice 建会话时把题目与标准答案**复制**进自己的库，之后判分是纯本地事务。bank 挂掉只影响"开新会话"，不影响"提交已开会话"——熔断降级能真正生效，正是因为依赖被切成一次性的快照拉取。

## 注册发现与网关

- Eureka 三要素：服务启动时**注册**、之后按 `lease-renewal-interval`（本配置 5s）**续约**、超过 `lease-expiration-duration`（10s）未续约被剔除；调用方按 `registry-fetch-interval-seconds: 5` 拉实例列表。
- 客户端负载均衡在调用时选一个实例替换 URL 里的服务名；`@LoadBalanced` 的作用就是给 WebClient/Feign 装上这个"先查注册表再选实例"的过滤器，没有它 `http://auth-service/...` 会走 DNS 然后失败。
- 网关的职责边界：路由 + 统一鉴权 + 限流 + 请求 ID 透传，**不承载业务规则**。网关只做"验签 + 透传身份"（`X-User-Id` / `X-User-Role`），授权判断仍在各服务里——网关是边界，服务自己是最后一道防线（内网直连可绕过网关）。
- 网关鉴权失败分两类返回：token 无效 → 401（客户端应重新登录）；auth-service 不可达 → 503（**鉴权是门禁，门禁坏了只能关门，不能默认放行**）。
- 限流参数语义：`replenishRate=2`（每秒补充 2 个令牌）+ `burstCapacity=5`（桶容量），突发第 6 个请求开始 429；Redis 令牌桶保证多网关实例共享同一配额。

## OpenFeign 与熔断

- Feign 把跨服务 HTTP 调用声明成接口，`name="bank-service"` 走服务名解析而非硬编码 IP；契约接口放 `cloud-common` 模块，provider 与 consumer 同依赖，**编译期就能发现签名漂移**。
- Feign 必须显式配超时（本项目 `connectTimeout: 2000` / `readTimeout: 3000`）：默认 60s 读超时，一次下游卡顿就能占满 Tomcat 线程池。
- 熔断器状态机 `CLOSED -> OPEN -> HALF_OPEN -> CLOSED`：滑动窗口内失败率 ≥ 50% 断开（窗口 5、最少 5 次，即连败 5 次触发），OPEN 10s 后自动转 HALF_OPEN 放 3 个探测请求，成功即闭合。
- 降级策略要说实话：建新会话**没有有意义的兜底数据**，所以这里是"快速失败 + 明确错误码"（503），不是"返回空卷"。能返回兜底数据的应该是允许质量下降的读接口（缓存、推荐）。
- 多层 retry 会造成流量乘法（网关 retry × Feign retry × 熔断内 retry）；本项目只在 outbox relay 里做"下轮重试"，调用链上不叠 retry。
- 配置中心（Spring Cloud Config / Nacos）在当前拆分中**未接入**：配置仍在各服务 `application.yml` 里，密钥用环境变量覆盖。它的价值（审计/回滚/灰度/敏感字段集中）要在多环境多实例时才兑现。

## 事务性发件箱（跨服务一致性）

- "写库 + 发消息"不是一个事务；Outbox 把消息当**一行数据**与业务写入同一个本地事务落库，再由独立 relay 轮询投递。业务成功与"消息待发"原子，剩下的是至少一次投递。
- 至少一次 ⇒ 消费端必须按 `event_key` 幂等；relay 在"发送成功但未标记 sent"时崩溃会重发，这是模式的固有属性，不是 bug。
- relay 与发布接口是两条独立事务：Kafka 挂了只是消息积压，**发布功能不受影响**——这正是相对"事务里直接 send"的优势。

- 外部源码索引（MustRemember）：[Spring Cloud Gateway](https://docs.spring.io/spring-cloud-gateway/reference/)、[Spring Cloud OpenFeign](https://docs.spring.io/spring-cloud-openfeign/reference/)、[Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)、[Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html)

# MustUnderstand

## 请求全链路（背下来，能从网关画到数据库）

```text
client -> gateway:8080 (RequestIdFilter 生成 X-Request-Id)
       -> AuthFilter 向 auth-service /internal/token/verify 验签，塞 X-User-Id/X-User-Role
       -> 路由表 lb://auth|bank|practice（Redis 令牌桶在 bank 路由上限流）
       -> 下游服务按 X-User-Id 授权
       -> practice 建会话时经 Feign 调 bank /internal/papers/{id}/snapshot
       -> bank 发布时 outbox_event 与业务同事务落库，relay 异步投 Kafka
```

## 源码索引（文件 -> 类/方法 -> 行号 -> 学习动作）

- [AuthFilter.java](../../cloud/gateway-service/src/main/java/com/allen/cloud/gateway/AuthFilter.java) 第 44 行定义受保护前缀 `/api/banks` `/api/practice`；第 60 行放行公开路径；第 70 行用**服务名** `http://auth-service/internal/token/verify` 调验签；第 76 行验签成功后把身份写进 `X-User-Id`；第 84-87 行区分 401（token 无效）与 503（auth 不可达）。学习动作：背写"验签 + 透传身份，不做业务判断"的三条决定。
- [gateway application.yml](../../cloud/gateway-service/src/main/resources/application.yml) 第 18-34 行三条显式路由（`lb://` 服务名 URI）；第 28-29 行限流参数。学习动作：解释为什么关掉 `discovery.locator.enabled`（自动路由按服务名生成 `/AUTH-SERVICE/**`，暴露内部拓扑且大小写陷阱）。
- [RequestIdFilter.java](../../cloud/gateway-service/src/main/java/com/allen/cloud/gateway/RequestIdFilter.java) 第 29-33 行请求无 ID 则生成 UUID、写入请求与响应头；order=-200 保证 401 响应也带 ID。学习动作：说明没有它为什么"三个服务的日志对不上一次请求"。
- [BankClient.java](../../cloud/cloud-common/src/main/java/com/allen/cloud/common/BankClient.java) 第 14 行 `@FeignClient(name="bank-service", path="/internal")` 声明式契约；[PracticeApplication.java](../../cloud/practice-service/src/main/java/com/allen/cloud/practice/PracticeApplication.java) 第 11 行 `@EnableFeignClients(basePackages="com.allen.cloud.common")` 扫描契约包。学习动作：说出契约模块模式解决什么问题（两处签名漂移上线才炸）。
- [BankSnapshotGateway.java](../../cloud/practice-service/src/main/java/com/allen/cloud/practice/BankSnapshotGateway.java) 第 37 行 `@CircuitBreaker(name="bankService", fallbackMethod="fallback")`；第 43-47 行 fallback 抛 503 并打日志。学习动作：背写"为什么是快速失败而不是兜底数据"。
- [practice application.yml](../../cloud/practice-service/src/main/resources/application.yml) 第 38-42 行 Feign 超时 2s/3s；第 43-48 行熔断参数（窗口 5、最少 5 次、失败率 50%、OPEN 10s、HALF_OPEN 放 3 个探测）。学习动作：算出"连打 6 次全败，第 5 次触发断开，第 6 次 CallNotPermitted 直接快速失败"。
- [PracticeService.java](../../cloud/practice-service/src/main/java/com/allen/cloud/practice/PracticeService.java) 第 45 行建会话**先查 clientToken 幂等再拉快照**（顺序反了重试会放大下游压力）；第 50 行 Feign 拉快照；第 70-88 行提交判分全在本库完成，重复提交直接返回上次结果。学习动作：解释为什么判分不依赖 bank。
- [BankService.java](../../cloud/bank-service/src/main/java/com/allen/cloud/bank/BankService.java) 第 69 行 `saveAndFlush` 撞 `(bank_id, version)` 唯一约束时第 70-72 行转 409（并发发布的兜底，靠数据库而不是应用层判断）；第 83 行 outbox_event 与业务写入**同一个事务**。学习动作：背写"查 max(version)+1 为什么必须配唯一约束"。
- [OutboxRelay.java](../../cloud/bank-service/src/main/java/com/allen/cloud/bank/OutboxRelay.java) 第 43 行 2s 周期轮询；第 46 行取未发送批次；第 49-51 行 `send().get(5s)` 同步等确认后 `markSent()`；投递失败 `return` 留待下轮。学习动作：画出"发送成功但未标记就崩溃"的重发时序，并指出消费端幂等键。
- [TokenService.java](../../cloud/auth-service/src/main/java/com/allen/cloud/auth/TokenService.java) 第 39 行 `issue`（HMAC-SHA256 签名 payload `userId|username|role|exp`）；第 49-63 行 `verify`；第 102-108 行 `constantTimeEquals` 定长比较防时序攻击。学习动作：说出签名防什么（篡改）、不防什么（泄露），以及为什么网关每请求都回验签。

## 一键复跑证据（本机已验证的命令）

```bash
./scripts/cloud-evidence.sh          # 拉起 MySQL/Redis/Kafka + 5 个服务，10 项证据落 output/
./scripts/cloud-evidence.sh status   # 查看当前进程/端口
./scripts/cloud-evidence.sh stop     # 只停 cloud 服务
```

证据共 10 项（注册发现 / 网关路由 / 统一鉴权 / Feign / 幂等 / 判分 / 数据所有权 / 发件箱 / 限流 / 熔断恢复），全部带 `[evidence]` 前缀，落盘 `output/cloud_evidence.log`，各服务自身日志在 `output/cloud_<name>.log`。
