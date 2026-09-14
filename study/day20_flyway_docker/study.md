# MustRemember

- Flyway 迁移只追加不修改；版本号决定执行顺序，checksum 检测历史文件改写；V1 建 7 表、V2 建 3 个查询索引、V3 建 import_job、V4 建 outbox_event/consumed_event，全库共 11 张表，再追加从 V5 起
- Docker 的 image 是不可变模板，container 是运行实例，volume 是独立持久状态，port 是宿主到容器的网络映射；根 compose 三服务：mysql:8.4（3307:3306）、redis:7.4-alpine（6380:6379）、app（8080，depends_on 两者 healthy，APP_CACHE_BACKEND=redis），数据卷 external 须预建
- Hibernate `ddl-auto=validate` 只检查 Entity 与数据库结构一致（application.yml:11），Flyway 负责结构变更和迁移历史（:17-18）；测试 H2 同样跑 Flyway
- V2 索引 SQL 的源码形态是：
  ```sql
  CREATE INDEX idx_paper_version_status_published_at
      ON paper_version (status, published_at);
  ```
- 源码索引（MustRemember）：[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 1-86 行的建表顺序；[V2__m0_query_indexes.sql](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql) 第 2-9 行三个查询索引；[V3__async_import_jobs.sql](../../app/src/main/resources/db/migration/V3__async_import_jobs.sql) 全 15 行；[V4__outbox_events.sql](../../app/src/main/resources/db/migration/V4__outbox_events.sql) 全 27 行

# MustUnderstand

- 兼容发布通常先增加兼容列/表和代码，再回填数据，最后收紧约束；删除或重命名需要多阶段发布
- H2 `MODE=MySQL` 不是 MySQL 的锁、索引选择和优化器完全替代；锁和 EXPLAIN 结论必须以真实 MySQL 为准（MysqlRealDbIntegrationTest，MYSQL_EVIDENCE 门控）
- compose 只服务 app 模块化单体：没有 Kafka、没有 cloud/ 六服务；Outbox 表虽由 V4 建好，但 Kafka 组件需 app.kafka.enabled=true 才装配
- 源码索引（MustUnderstand）：[docker-compose.yml](../../docker-compose.yml) 三服务的 volume/healthcheck/depends_on；[application.yml](../../app/src/main/resources/application.yml) 的 datasource、Flyway 与 JPA 启动顺序
- 关键配置：[docker-compose.yml](../../docker-compose.yml)、[V2__m0_query_indexes.sql](../../app/src/main/resources/db/migration/V2__m0_query_indexes.sql)；官方：[Flyway](https://documentation.red-gate.com/flyway)、[Compose](https://docs.docker.com/compose/)
