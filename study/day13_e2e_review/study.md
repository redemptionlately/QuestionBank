# MustRemember

- Spring Boot 集成测试通过 `@SpringBootTest` 加载应用上下文；`MockMvc` 在进程内走 MVC；真实 HTTP 客户端经过端口、过滤器、Controller、Service 和数据库
- 端到端业务链的输入依赖关系是：认证主体 -> 题库 -> 草稿版本 -> 发布版本 -> 练习会话 -> 答案 -> 提交结果 -> 错题聚合
- 测试夹具必须固定角色、资源归属、版本状态、答案集合和请求头；断言同时覆盖 HTTP 状态、错误 code、持久化状态和返回 JSON
- 集成测试结构的源码骨架是：
  ```java
  @SpringBootTest
  class M0FlowIntegrationTest {
      @Autowired MockMvc mvc;
      @Autowired UserAccountRepository users;
      @Test void submitIsIdempotent() throws Exception {
          // 同一 Idempotency-Key 的第二次 POST 返回第一次持久化的结果
      }
  }
  ```
- 全量测试规模：27 个测试类、119 个 @Test；默认 mvn test 为 90 通过 / 29 环境门禁跳过 / 0 失败（REDIS/MYSQL/KAFKA/SHARDING/READ_REPLICA 五类门控）
- 四态边界：① app 已实现（缓存三实现/Redis 锁/Redis 限流/Kafka Outbox/读写分离/AOP 延迟/健康指标/RequestId/导入重试/MyBatis 错题）② 已测试（默认 90 + 门控 29）③ 仅设计（PDF 抽取、对象存储、租约恢复、全链路追踪）④ cloud/ 六个独立微服务演示，与 app 不共享代码
- 源码索引（MustRemember）：[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 39-90 行的完整发布/提交链路和第 93-151 行的角色、题型、错误断言

# MustUnderstand

- Controller 负责协议绑定，Service 负责授权与状态不变量，Repository 负责查询；事务提交后才形成数据库事实
- 单元测试隔离一类规则，集成测试验证 Bean、事务和数据库映射，端到端测试验证真实协议与部署依赖；三者覆盖的故障层不同
- 源码索引（MustUnderstand）：[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 154-240 行的回滚、同 key 并发、索引和异步导入测试；门控证据测试见 RedisCacheIntegrationTest/RedisLockAndRateLimitIntegrationTest/MysqlRealDbIntegrationTest/OutboxKafkaIntegrationTest/ReadReplicaIntegrationTest/ShardingEvidenceTest；[README.md](../../README.md) 的 Verification/Evidence 边界
