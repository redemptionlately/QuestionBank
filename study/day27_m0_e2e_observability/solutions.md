# Day27 M0 E2E & Observability · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `M0FlowIntegrationTest`、`application.yml`、`output/real_mysql_m0_verification_20260818.log`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`M0FlowIntegrationTest` 39-90 行主链路；README Verification 命令；`application.yml` Actuator/日志配置；真实 HTTP 证据日志。

---

### Q2. 从零启动并保存一份 HTTP E2E。
顺序：`docker compose up -d mysql`（等 healthy）→ 启动应用（Flyway→validate→就绪）→ `/actuator/health` UP → login 拿 token → createBank → createDraft → publish → 学生 login → create practice → 逐题 PUT answer → submit（带 Idempotency-Key）→ 同 key retry → GET wrong-questions。每步记录请求、状态码、关键响应字段，形成可复现证据文件。

---

### Q3. 检查迁移和幂等。
- 迁移：`SELECT version,success FROM flyway_schema_history;` 确认 V1/V2 success=1，重启不重复；
- 幂等：同一 Idempotency-Key 两次 submit，对比两次响应 JSON 完全一致，且 wrongCount 不增长、submission_item 每题仍只有一行。

---

### Q4. 健康探针 / 应用日志 / Flyway 历史 / 业务响应各自能证明什么、不能证明什么。

| 证据 | 能证明 | 不能证明 |
|---|---|---|
| Actuator health/metrics | 进程存活、被覆盖依赖（DB）连通、JVM 指标 | 登录/发布/提交业务链路正确 |
| 应用日志 | 内部走到了哪条路径、异常栈、耗时、requestId 关联 | 不能替代断言；含主观文本，可能漏 |
| flyway_schema_history | 结构迁移版本、checksum、是否成功 | 不证明业务数据正确 |
| 业务响应 + DB 查询 | 功能事实（状态、总分、落库行） | 不证明高并发/大规模下表现 |
结论：任何单一证据都不能证明整个系统，需多层交叉。

---

### Q5. 写出 `set -o pipefail | tee` 证据命令，列出不得入日志的字段。
```bash
set -o pipefail                                   # 管道中任一步失败整体失败，不会被 tee 掩盖
./mvnw -B test 2>&1 | tee output/mvn_test_$(date +%Y%m%d).log
```
- **可记录**：requestId、资源 id、状态转换、耗时、命令、时间、commit/版本、机器/容器、响应状态与摘要；
- **禁止记录**：密码、完整 token / Authorization 头、答案正文（隐私场景）、数据库凭据、堆栈中的敏感参数。

---

### Q6. 为同一 Service 规则分别设计 Mockito / Spring 集成 / Testcontainers 测试，并说明各自不能证明什么。
以“空名称不能创建题库”为例：
1. **Mockito 单元测试**：mock Repository，调 `createBank`，断言抛 `ApiException` 且 `verify(banks,never()).save(any())`。快、隔离，但**不证明** SQL 映射、约束、事务行为；
2. **Spring 集成测试（@SpringBootTest + H2）**：真实 Bean/事务/Flyway，断言不仅抛异常且表里无新增。证明装配与事务回滚，但 H2 `MODE=MySQL` **不证明** InnoDB 锁/优化器行为；
3. **Testcontainers（真实 MySQL 8.4 容器）**：在与生产一致的数据库上验证约束、锁、方言，最真实，但慢、依赖 Docker，仍**不证明**生产规模性能。

---

### Q7. 测试金字塔各层速度/隔离/真实性差异。
| 层 | 速度 | 隔离性 | 真实性 | 典型 |
|---|---|---|---|---|
| 单元测试 | 最快 | 最高（只测一个类，替身协作者） | 低 | Mockito/JUnit |
| 切片测试 | 快 | 高（只装载 MVC/JPA 层） | 中 | @WebMvcTest/@DataJpaTest |
| 集成测试 | 中 | 中（真实 Bean+内存库） | 较高 | @SpringBootTest+H2 |
| 契约测试 | 中 | 中 | 保证请求/响应 schema 兼容 | 消费者驱动契约 |
| E2E/Testcontainers | 慢 | 低（全栈+真实中间件） | 最高 | 真实 HTTP+容器 |
数量分布应“底多顶少”；测试替身不能证明真实数据库锁与网络行为，生产规模性能只能靠压测。

---

## External

### E1. 停掉 MySQL 保存失败日志。
`docker compose stop mysql` 后请求业务接口，记录：health 变为 DOWN/相关指示器失败、业务接口 500 `INTERNAL_ERROR`、日志中连接获取失败异常栈（不含凭据）。证明“进程存活≠依赖就绪≠业务可用”三个层次，以及兜底处理器不泄露内部信息。

### E2. 设计 requestId / 指标。
- **requestId**：过滤器为每个请求生成 UUID 写入 MDC，日志 pattern 输出，响应体/响应头回带，排障用它串起一条链路；
- **指标分层**：平均延迟（均值，易被长尾拉偏）、P95/P99（尾部体验）、成功率（结果层）、锁等待次数/时长（数据库竞争层）——它们度量不同对象，不能互相替代。本项目 `RequestMetrics`/`MetricsController` 提供进程内原子计数与窗口指标，生产需对接 Micrometer + Prometheus。

### E3. 为同一业务规则分别写 mock 交互断言、真实数据库断言、契约字段断言。
以提交幂等为例：mock 层 `verify(repo).findByIdForUpdate(id)` 验证调用路径；真实库层断言两次提交后 session 仅一行 SUBMITTED、result JSON 一致；契约层断言响应**字段集合与类型**稳定（sessionId:number、totalScore:number、answers:array、每项含 questionId/correct/score），保证前端不会因字段缺失而崩。
