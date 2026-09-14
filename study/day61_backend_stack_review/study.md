# MustRemember

- 常见 Java 后端链路可归纳为：Java/Spring API、MySQL 事务事实、Redis 读优化、MQ 异步解耦、ES 搜索投影、Linux/Docker 部署和指标/日志/追踪。
- 每个组件都要回答五件事：数据是否为事实、失败如何重试、重复是否幂等、容量瓶颈在哪里、如何观测和回滚。
- 项目面试不能罗列技术名词；必须从 M0 的发布、提交或异步导入举例，说明选择、代码位置、测试证据、限制和后续演进。
- 求职前的最低可投状态是：Java 基础题能写，Spring 请求链能画，SQL 能看执行计划，能解释并发/事务，能排查 Linux 服务，并能完整讲一个真实项目难点。
- 外部源码索引（MustRemember）：[Spring Guides](https://spring.io/guides)、[MySQL Reference](https://dev.mysql.com/doc/refman/8.4/en/)、[Java 21 API](https://docs.oracle.com/en/java/javase/21/)

# MustUnderstand

- 没有“学完全部 Java 就保证 Offer”；岗位、城市、年限和面试题差异会改变重点。路线提供能力覆盖，真实掌握要靠重复实现、测试和面试反馈。
- M0 当前是模块化单体：本地缓存/固定窗口限流/原子指标/@Async 任务为默认基线；Redis 缓存/锁/令牌桶限流、Kafka Outbox、读写分离均已编码实现但靠配置键/profile 条件开启，其证据测试由 REDIS_EVIDENCE/KAFKA_EVIDENCE/READ_REPLICA_EVIDENCE/MYSQL_EVIDENCE 门控（默认构建跳过）；ShardingSphere 仅 test scope 证据；cloud/ 是不共享代码的独立微服务演示；ES 未接入。任何能力只有实际接入、跑过对应测试并留存日志，才可写成项目经历。
- 最有效的补强顺序是先巩固 Java/Spring/MySQL 和算法，再根据目标 JD 选择 Redis、MQ、ES、微服务或中间件深入，避免同时堆多个未掌握组件。
- 外部源码索引（MustUnderstand）：[Google SRE Workbook](https://sre.google/workbook/)、[OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/)
