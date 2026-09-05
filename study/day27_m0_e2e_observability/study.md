# MustRemember

- 真实 E2E 的顺序是 MySQL healthy -> Flyway -> health -> login -> bank -> draft -> publish -> practice -> answer -> submit -> retry -> wrong questions
- 原始日志字段包括命令、时间、commit/版本、机器/容器、请求状态、响应摘要和错误输出；敏感 token/密码不入日志
- Actuator 证明进程和依赖探针，应用日志证明内部路径，迁移历史证明结构，业务响应和数据库查询证明功能事实
- 单元测试隔离一个规则，切片测试验证 MVC/JPA 层，集成测试验证 Bean、事务和数据库，契约测试验证请求/响应兼容；测试替身不能证明真实数据库锁和网络行为
- Mockito 的 mock 验证交互和参数，Testcontainers 用真实容器验证数据库/中间件；两者都不能替代生产规模压测
- 证据记录的 shell 形态是：
  ```bash
  set -o pipefail
  ./mvnw -B test 2>&1 | tee output/mvn_test_YYYYMMDD.log
  ```
- 源码索引（MustRemember）：[M0FlowIntegrationTest.java](../../src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 39-90 行的主链路测试；[README.md](../../README.md) Verification 命令

# MustUnderstand

- `requestId`、资源 ID、状态和耗时适合日志；密码、token、答案正文、数据库凭据和完整 Authorization 不应记录
- 平均延迟描述均值，P95 描述尾部请求，成功率描述结果，锁等待描述数据库竞争；它们属于不同测量层
- 源码索引（MustUnderstand）：[real_mysql_m0_verification_20260818.log](../../output/real_mysql_m0_verification_20260818.log) 的真实 HTTP 证据；[application.yml](../../src/main/resources/application.yml) 的日志/Actuator 配置
- 关键配置/证据：[application.yml](../../src/main/resources/application.yml)、[real_mysql_m0_verification_20260818.log](../../output/real_mysql_m0_verification_20260818.log)；官方：[Actuator](https://docs.spring.io/spring-boot/reference/actuator.html)
- 外部源码索引（MustRemember）：[Mockito verification](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html) 的 `when/verify/ArgumentCaptor`；[Testcontainers JDBC](https://java.testcontainers.org/modules/databases/jdbc/)
- 外部源码索引（MustUnderstand）：[Testing Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html) 的速度、隔离和真实性取舍
