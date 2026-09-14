# Day35 M2 Failure Injection · 题目与标准解答（Solutions）

> 依据：`study.md` + 重构后真实代码：`ops/DatabaseLatencyHealthIndicator`（DB 探针）、`common/GlobalExceptionHandler`（统一错误契约）、`cache/RedisPublishedPaperCache`（Redis 故障降级）、`event/OutboxRelay`（Kafka 故障积压/重试）、`importjob/ImportJobRetrySweeper`（失败退避重试）。注意：**app 单体不依赖 Resilience4j，注解式熔断/隔离舱的真实代码只存在于独立的 `cloud/practice-service`**，两者不共享代码。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
对照本项目真实组件：
- 故障注入：`docker compose stop mysql`（根 [docker-compose.yml](../../docker-compose.yml) 的 mysql 服务，容器 question-bank-m0-mysql，宿主端口 3307）；
- 健康探针：[ops/DatabaseLatencyHealthIndicator.java:18-19](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java)，health() :30 执行 `SELECT 1`（:33），>500ms（SLOW_QUERY_THRESHOLD_MS=500，:21）或抛异常 → Health.down（:43），details 带 probeQuery/latencyMs；经 `/actuator/health` 暴露（SecurityConfig:47-48 放行 health/**，[application.yml:32](../../app/src/main/resources/application.yml) 开 liveness/readiness probes）；
- 业务错误契约：[common/GlobalExceptionHandler.java:19-20](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) 共 6 个 @ExceptionHandler，兜底 Exception 在 :50，统一经 response()（:59）产出 `ErrorResponse(code,message,requestId,timestamp)`；[application.yml:35](../../app/src/main/resources/application.yml) `server.error.include-message=never`，不泄露 SQL/连接串；
- 恢复：`docker compose start mysql` 等待 healthcheck healthy 后探针回到 UP。

---

### Q2. 停止 MySQL 验证 health/API/恢复并保存证据。
实验步骤（有明确停止条件与回滚）：
1. 基线：`/actuator/health` UP、业务接口 200，留存日志；
2. 注入：`docker compose stop mysql`；
3. 观测（健康层）：DatabaseLatencyHealthIndicator 的 `SELECT 1` 抛异常 → db 指示器 DOWN（该路径有单测 `OpsComponentsTest.healthIndicatorReportsDownWhenDatabaseIsUnreachable`，:38）；
4. 观测（API 层）：依赖 DB 的业务接口连接失败，经 GlobalExceptionHandler 兜底为 500，错误体为统一 ErrorResponse、带 requestId（由 RequestIdFilter 写入 MDC/响应头），无连接串/密码/SQL 外泄；
5. 恢复：`docker compose start mysql`，等容器 healthcheck（mysqladmin ping）通过，探针 UP、接口恢复 200；
6. 全程 `Tee-Object`/tee 保存带时间戳的日志作为证据。
停止条件：出现非预期错误格式或进程崩溃即停止；回滚方式：重新启动数据库。
同类、代码已支持的另两个注入实验：①停 Redis——`RedisPublishedPaperCache.readThrough`（:76）捕获 Redis 异常计入 failures 并**降级回库**（集成测试 `RedisCacheIntegrationTest.cacheFallsBackToDatabaseWhenRedisIsDown` :106，门控 REDIS_EVIDENCE）；②停 Kafka——Outbox 事件保持 PENDING、Relay 失败 requeue，达 `app.outbox.max-attempts=5` 置 FAILED，**发布主事务不被 broker 故障阻断**（OutboxRelayAndConsumerTest 5 用例固化，含模拟 broker unreachable 场景）。

---

### Q3. 写出超时、重试、熔断、降级、隔离舱触发条件与不可重试错误。
| 模式 | 触发条件 | 作用 | 本项目对应物（如实区分） |
|---|---|---|---|
| Timeout 超时 | 等待超过预算（且 < 上游 SLA） | 快速失败，释放线程 | read-replica Hikari `connectionTimeout=5000`（ReadReplicaDataSourceConfig.hikariOf :57-67）；cloud 网关 httpclient 2s/8s、Feign 2s/3s（仅 cloud/） |
| Retry 重试 | 瞬态错误（连接瞬断、限时不可用、可恢复 5xx） | 掩盖偶发抖动 | OutboxRelay 同步 send 失败 requeue、达 maxAttempts=5 置 FAILED（:59）；Kafka 消费者错误处理器重试 2 次间隔 200ms 后进 .DLT；ImportJobRetrySweeper 指数退避 30<<n 秒、封顶 600、最多 3 次 |
| Circuit Breaker 熔断 | 滑动窗口失败率/慢调用率超阈值 | 短路保护下游 | **app 单体未引入 Resilience4j，无此组件**；真实实现仅在 cloud/practice-service 的 BankSnapshotGateway（@CircuitBreaker(name="bankService")，窗口 5/最少 5/失败率 50%/OPEN 10s/半开 3，TimeLimiter 4s） |
| Fallback 降级 | 被熔断/超时/失败后 | 返回有限能力 | app：Redis 缓存读失败降级回数据库（RedisPublishedPaperCache）；cloud：bankService fallback 为**快速失败 503** 而非兜底数据 |
| Bulkhead 隔离舱 | 线程/信号量占满 | 把故障限制在单一依赖 | app 未接入；双异步线程池（import 2/4/100、general 2/8/200，InfrastructureConfig:22-53）起到线程资源隔离的等价作用；cloud 未配 Bulkhead（resume "明确不写"清单同口径） |

**不可重试错误**：权限不足(403)、参数错误(400)、唯一键冲突(409)、数据损坏/格式非法——重试结果不会改变，只会放大流量。Outbox 对"序列化失败/blank payload"直接让整个事务回滚（OutboxPublisher :59 起），正是不做无意义重试的体现。指数退避 `delay=min(max,base*2^n)+jitter`，限制最大次数，避免多层重试相乘形成重试风暴（ImportJobRetrySweeper 的 30/60/120…封顶 600 即项目内真实实例）。

---

### Q4. 画出 CLOSED→OPEN→HALF_OPEN 熔断状态机。
```
            失败率/慢调用率 ≥ 阈值（窗口5次调用、失败率50%）
 CLOSED ───────────────────────────▶ OPEN（直接拒绝调用，走 fallback，冷却 10s）
   ▲                                   │ 冷却结束，半开放行 3 个探测请求
   │ 探测成功（达到成功阈值）            ▼
   └────────────────────────────── HALF_OPEN（试探下游是否恢复）
   ▲                                   │ 探测再次失败
   └───────────────────────────────────┘ 回到 OPEN（重新冷却）
```
- CLOSED：正常放行并统计；OPEN：快速失败不调用下游；HALF_OPEN：有限探测，成功则关闭熔断、失败则重新打开。Resilience4j 用滑动窗口统计 failure rate / slow call rate，配置 minimum-number-of-calls、wait-duration、permitted-calls-in-half-open。
- **项目口径**：上述参数（5/5/50%/10s/3/TimeLimiter 4s）来自 [cloud/practice-service/application.yml](../../cloud/practice-service/src/main/resources/application.yml) 的 bankService 实例，fallback 抛 503"熔断已生效，快速失败"；app 模块化单体没有熔断器，面试时必须如实说明，不能把 cloud 的配置说成 app 的能力。

---

### Q5. 为数据库不可用写出健康/API/恢复/数据一致性四层断言。
1. **进程存活层**：JVM 进程仍在、8080 端口仍监听（DB 故障不拖死进程；readiness 摘流量、liveness 不重启，k8s 清单 [deploy/k8s/app.yaml](../../deploy/k8s/app.yaml) 两探针分离即此目的）；
2. **健康探针层**：`/actuator/health` 为 DOWN，DatabaseLatencyHealthIndicator 标记 DOWN 且 details 含 probeQuery="SELECT 1" 与 latencyMs/异常（探针免 token 可被编排系统探测，M0OpsIntegrationTest.containerProbesAreAccessibleWithoutToken :28 固化）；
3. **业务 API 层**：依赖 DB 的接口经 GlobalExceptionHandler 兜底返回 500、ErrorResponse 含 code/requestId/timestamp、无敏感信息；不依赖 DB 的路径可降级（如 Redis 缓存故障方向相反：Redis 挂时读路径降级回库）；
4. **数据一致性层**：故障期间未提交事务全部回滚、无半写入；恢复后已提交数据完好，提交接口靠三层幂等保证重放等价——Redis 锁（RedisGuardedPracticeSubmitter，减冲突）→ DB SELECT…FOR UPDATE 行锁 → uk_submission_question/submission_key 唯一约束（最终兜底）。数据库恢复 ≠ 故障期间请求成功，失败请求由客户端按 Idempotency-Key 重试。

---

## External

### E1. 模拟连接池耗尽。
把 HikariCP `maximumPoolSize` 调小并让查询慢/持锁（app 主数据源走默认池，read-replica profile 可在 hikariOf :57-67 改 maxPoolSize=5/connectionTimeout=5000），制造连接被占满：后续请求在 connectionTimeout 后快速失败而非无限等待，观察 Tomcat 线程是否被级联拖垮、双异步池（import/general）是否把后台任务影响限制在本池。验证超时的价值，并确认错误仍走 GlobalExceptionHandler 的统一契约。真 MySQL 侧的锁等待/死锁行为可对照门控测试 MysqlRealDbIntegrationTest 的 :227（FOR UPDATE 锁等待超时）与 :259（死锁牺牲者回滚）。

### E2. 写故障复盘（最小闭环）。
按六段式：**现象**（什么时间、哪些接口、什么表现）→ **复现条件**（如何稳定复现）→ **根因**（追到代码/配置/资源层）→ **修复**（具体改动）→ **回归证据**（测试/压测/注入验证结果，本项目对应 OpsComponentsTest/RedisCacheIntegrationTest/OutboxRelayAndConsumerTest/ImportJobRetrySweeperTest 等可重复执行的用例）→ **残余限制**（还有什么没覆盖、监控如何补）。故障注入是验证"已设计的状态转换与恢复路径"，不是随机制造事故，每个实验都要有停止条件与回滚。
