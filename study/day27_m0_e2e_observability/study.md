# MustRemember

- 真实 E2E 的顺序是 mysql/redis healthy -> Flyway V1~V4 -> ddl-auto=validate -> health -> login -> bank -> draft -> publish -> practice -> answer -> submit -> retry -> wrong questions
- 原始日志字段包括命令、时间、commit/版本、机器/容器、请求状态、响应摘要和错误输出；敏感 token/密码不入日志；logback pattern 带 `[%X{requestId:-}]`
- Actuator 证明进程和依赖探针（DatabaseLatencyHealthIndicator 跑 SELECT 1、阈值 500ms），应用日志证明内部路径（ServiceLatencyAspect 超 200ms warn），迁移历史证明结构，业务响应和数据库查询证明功能事实
- 单元测试隔离一个规则，切片/ArchUnit 验证分层约束，集成测试验证 Bean、事务和 H2 库，契约测试验证请求/响应兼容；本项目真实中间件层不用 Testcontainers，而用 *_EVIDENCE 环境变量门控（默认构建 90 通过 / 29 门控跳过 / 0 失败）
- 证据记录的 shell 形态是：
  ```bash
  set -o pipefail
  ./scripts/mvn.sh -B test 2>&1 | tee output/mvn_test_YYYYMMDD.log
  # PowerShell: .\mvnw.cmd -B test 2>&1 | Tee-Object output\mvn_test.log
  ```
- 源码索引（MustRemember）：[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 39-91 行主链路（全类 8 法）；[M0OpsIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0OpsIntegrationTest.java) 探针/指标/requestId 三法

# MustUnderstand

- `requestId`（RequestIdFilter：透传/生成 X-Request-Id、MDC key="requestId"、finally remove）、资源 ID、状态和耗时适合日志；密码、token、答案正文、数据库凭据和完整 Authorization 不应记录
- 平均延迟描述均值，P95/P99 描述尾部请求，成功率描述结果，锁等待描述数据库竞争；RequestMetrics 是进程内 AtomicLong，Prometheus 导出走 /actuator/prometheus，它们属于不同测量层
- 依赖故障姿态不同：DB 不可用 health DOWN、业务 500；Redis 不可用缓存降级回库、锁/限流缺失时由 DB 行锁与唯一键兜底正确性
- 源码索引（MustUnderstand）：[real_mysql_m0_verification_20260818.log](../../output/real_mysql_m0_verification_20260818.log) 的真实 HTTP 证据；[application.yml](../../app/src/main/resources/application.yml) 第 28-35 行 Actuator/错误配置；[RequestIdFilter.java](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) 第 22-41 行
- 关键配置/证据：[application.yml](../../app/src/main/resources/application.yml)、[real_mysql_m0_verification_20260818.log](../../output/real_mysql_m0_verification_20260818.log)；官方：[Actuator](https://docs.spring.io/spring-boot/reference/actuator.html)
- 外部源码索引（MustRemember）：[Mockito verification](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html) 的 `when/verify/ArgumentCaptor`；[Testcontainers JDBC](https://java.testcontainers.org/modules/databases/jdbc/)
- 外部源码索引（MustUnderstand）：[Testing Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html) 的速度、隔离和真实性取舍
