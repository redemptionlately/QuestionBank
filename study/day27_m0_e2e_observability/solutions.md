# Day27 M0 E2E & Observability · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `M0FlowIntegrationTest`/`M0OpsIntegrationTest`、[application.yml](../../app/src/main/resources/application.yml)、ops 包两组件、`output/real_mysql_m0_verification_20260818.log`，以及基线构建实测（119 个 @Test：默认 90 通过、29 环境门控跳过、0 失败）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[M0FlowIntegrationTest.java:39-91](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 主链路 adminPublishesAndStudentSubmitsIdempotently（全类 8 个 @Test）；[M0OpsIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0OpsIntegrationTest.java) 3 个探针/指标/requestId 用例（:28/:39/:55）；[application.yml:28-32](../../app/src/main/resources/application.yml) Actuator 暴露（health,info,metrics,prometheus + probes）；ops 包 ServiceLatencyAspect（慢调用阈值 200ms）、DatabaseLatencyHealthIndicator（SELECT 1、慢查询阈值 500ms）；真实 HTTP 证据日志 output/real_mysql_m0_verification_20260818.log。

---

### Q2. 从零启动并保存一份 HTTP E2E。
顺序：`docker compose up -d`（mysql 与 redis 均 healthy 后 app 才启动，depends_on condition: service_healthy）→ 应用启动（Flyway V1~V4 → ddl-auto=validate → 就绪）→ `/actuator/health` UP（探针免 token，SecurityConfig:47）→ login 拿 token → createBank → createDraft → publish（同事务写 Outbox、afterCommit 淘汰缓存）→ 学生 login → create practice → 逐题 PUT answer → submit（带 Idempotency-Key）→ 同 key retry → GET /api/wrong-questions（MyBatis 动态 SQL）。每步记录请求、状态码、关键响应字段，用 `set -o pipefail ... | tee` 形成可复现证据文件；前端另有 tools/ui-smoke.cjs 真浏览器 10 步冒烟。

---

### Q3. 检查迁移和幂等。
- 迁移：`SELECT version,success FROM flyway_schema_history;` 确认 **V1~V4** 共 4 行 success=1，重启不重复（当前 11 张表：V1 七表 + V3 import_job + V4 outbox_event/consumed_event）；
- 幂等：同一 Idempotency-Key 两次 submit，对比两次响应 JSON 完全一致（并发场景由 concurrentSubmitWithSameKeyReturnsOneStableResult :179 固化），且 wrong_count 不增长、submission_item 每题仍只有一行（uk_submission_question 兜底）。

---

### Q4. 健康探针 / 应用日志 / Flyway 历史 / 业务响应各自能证明什么、不能证明什么。

| 证据 | 能证明 | 不能证明 |
|---|---|---|
| Actuator health（含 DatabaseLatencyHealthIndicator 执行 SELECT 1，>500ms/异常即 DOWN）/metrics/prometheus | 进程存活、被覆盖依赖（DB）连通与探测延迟、JVM/自定义指标 | 登录/发布/提交业务链路正确 |
| 应用日志（pattern 带 `[%X{requestId:-}]`，MDC key 固定 requestId） | 内部走到了哪条路径、异常栈、慢调用（>200ms warn）、requestId 关联 | 不能替代断言；含主观文本，可能漏 |
| flyway_schema_history | 结构迁移版本、checksum、是否成功 | 不证明业务数据正确 |
| 业务响应 + DB 查询 | 功能事实（状态、总分、落库行） | 不证明高并发/大规模下表现 |
结论：任何单一证据都不能证明整个系统，需多层交叉；M0OpsIntegrationTest 三个用例分别把“探针免 token、指标认证可见、公开文档与响应带 X-Request-Id”做成自动化断言。

---

### Q5. 写出 `set -o pipefail | tee` 证据命令，列出不得入日志的字段。
```bash
set -o pipefail                                   # 管道中任一步失败整体失败，不会被 tee 掩盖
./scripts/mvn.sh -B test 2>&1 | tee output/mvn_test_$(date +%Y%m%d).log
```
Windows PowerShell 等价：`.\mvnw.cmd -B test 2>&1 | Tee-Object -FilePath output\mvn_test.log`（`$LASTEXITCODE` 判定成败）。
- **可记录**：requestId、资源 id、状态转换、耗时、命令、时间、commit/版本、机器/容器、响应状态与摘要；
- **禁止记录**：密码、完整 token / Authorization 头、答案正文（隐私场景）、数据库凭据、堆栈中的敏感参数。登录失败统一文案、`server.error.include-message: never`（[application.yml:35](../../app/src/main/resources/application.yml)）是同一原则的代码侧落地。

---

### Q6. 为同一 Service 规则分别设计 Mockito / Spring 集成 / 真实中间件测试，并说明各自不能证明什么。
以“空名称不能创建题库”为例，并对照本项目真实分层：
1. **Mockito 单元测试**：mock Repository，调 `createBank`，断言抛 `ApiException` 且 `verify(banks,never()).save(any())`。快、隔离，但**不证明** SQL 映射、约束、事务行为（项目实例：OutboxTest 9 法、OpsComponentsTest 5 法、ImportJobRetrySweeperTest 5 法均为纯 Mockito/单元）；
2. **Spring 集成测试（@SpringBootTest + H2 MODE=MySQL + Flyway）**：真实 Bean/事务/迁移，断言不仅抛异常且表里无新增（项目实例：M0FlowIntegrationTest 8 法、TransactionFailureModeTest 5 法、WrongQuestionQueryMapperTest 8 法，默认运行）。证明装配与事务回滚，但 H2 **不证明** InnoDB 锁/优化器行为；
3. **真实中间件测试**：本项目不用 Testcontainers，而是 `@EnabledIfEnvironmentVariable(*_EVIDENCE=true)` 门控 + 本地真实 MySQL/Redis/Kafka/主从（MysqlRealDbIntegrationTest 7 法、Redis* 三个类 11 法、Kafka* 2 法、ReadReplica 3 法、ShardingEvidence 6 法，共 29 个默认跳过）。方言/锁/网络行为最真实，但需自备环境、慢，仍**不证明**生产规模性能；Testcontainers 是同类目标的另一种工程选择（容器随测试启停）。

---

### Q7. 测试金字塔各层速度/隔离/真实性差异。
| 层 | 速度 | 隔离性 | 真实性 | 本项目典型 |
|---|---|---|---|---|
| 单元测试 | 最快 | 最高（只测一个类，替身协作者） | 低 | OutboxTest、OpsComponentsTest、common 子包 3 类 |
| 切片/架构测试 | 快 | 高（ArchUnit 无 Spring 上下文） | 中 | ArchitectureTest 5 规则 |
| 集成测试 | 中 | 中（真实 Bean+H2+Flyway） | 较高 | M0Flow/M0Ops/Transaction*/WrongQuestion* |
| 契约测试 | 中 | 中 | 保证请求/响应 schema 兼容 | cloud-common 共享 Feign 契约（编译期发现签名漂移） |
| E2E/真实中间件 | 慢 | 低（全栈+真实中间件，环境门控） | 最高 | *_EVIDENCE 门控 29 法、ui-smoke.cjs |
数量分布应“底多顶少”（默认构建 90 法秒级跑完，证据型按需开启）；测试替身不能证明真实数据库锁与网络行为，生产规模性能只能靠 tools/loadgen 开环压测。

---

## External

### E1. 停掉 MySQL 保存失败日志。
停库后请求业务接口，记录：DatabaseLatencyHealthIndicator 的 SELECT 1 抛异常 → health DOWN（details 带 probeQuery="SELECT 1" 与 failure）、业务接口经 GlobalExceptionHandler 兜底返回 500（不暴露堆栈/SQL）、日志中连接获取失败异常栈（不含凭据）。证明“进程存活≠依赖就绪≠业务可用”三个层次。对照面：Redis 停摆时缓存链不崩——RedisPublishedPaperCache 捕获异常降级回库（RedisCacheIntegrationTest.cacheFallsBackToDatabaseWhenRedisIsDown，门控 REDIS_EVIDENCE），两类依赖故障的设计姿态不同。

### E2. 设计 requestId / 指标。
- **requestId**：RequestIdFilter（common 包 :22-41）优先透传入站 `X-Request-Id` 头、缺失则 UUID，写 MDC（key 固定字符串 `"requestId"`）与响应头，finally 里 MDC.remove 防线程复用串号；logback pattern `[%X{requestId:-}]` 输出，ErrorResponse 带 requestId 字段，排障用它串起一条链路；
- **指标分层**：RequestMetrics 三个 AtomicLong（requests/failures/totalLatencyNanos，经 GET /api/metrics 认证可见）；Micrometer + micrometer-registry-prometheus 暴露 /actuator/prometheus；平均延迟易被长尾拉偏、P95/P99 度量尾部、成功率度量结果、锁等待次数/时长度量数据库竞争——度量对象不同，不能互相替代。

### E3. 为同一业务规则分别写 mock 交互断言、真实数据库断言、契约字段断言。
以提交幂等为例：mock 层 `verify(repo).findByIdForUpdate(id)` 验证调用路径（对照 OutboxRelayAndConsumerTest 的纯 Mockito 交互断言）；真实库层断言两次提交后 session 仅一行 SUBMITTED、result JSON 一致（M0Flow 并发用例 / 门控 MysqlRealDb 用例）；契约层断言响应**字段集合与类型**稳定（SubmitResult: sessionId:number、totalScore:number、maxScore:number、answers:array，每项 GradedAnswer 含 questionId/correct/score），保证前端不会因字段缺失而崩。
