# 必须会背会写

- Flyway 迁移只追加不修改；版本号决定执行顺序，checksum 检测历史文件改写；V1 建表、V2 建查询索引
- Docker 的 image 是不可变模板，container 是运行实例，volume 是独立持久状态，port 是宿主到容器的网络映射
- Hibernate `ddl-auto=validate` 只检查 Entity 与数据库结构一致，Flyway 负责结构变更和迁移历史
- V2 索引 SQL 的源码形态是：
  ```sql
  CREATE INDEX idx_paper_version_status_published_at
      ON paper_version (status, published_at);
  ```
- 源码索引（会背会写）：[V1__m0_schema.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V1__m0_schema.sql) 第 1-86 行的建表顺序；[V2__m0_query_indexes.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V2__m0_query_indexes.sql) 第 2-9 行的三个查询索引

# 必须理解

- 兼容发布通常先增加兼容列/表和代码，再回填数据，最后收紧约束；删除或重命名需要多阶段发布
- H2 `MODE=MySQL` 不是 MySQL 的锁、索引选择和优化器完全替代；锁和 EXPLAIN 结论必须以真实 MySQL 为准
- 源码索引（必须理解）：[docker-compose.yml](../../../../projects/java-question-bank-m0/docker-compose.yml) 的 volume/healthcheck；[application.yml](../../../../projects/java-question-bank-m0/src/main/resources/application.yml) 的 datasource、Flyway 与 JPA 启动顺序
- 关键配置：[docker-compose.yml](../../../../projects/java-question-bank-m0/docker-compose.yml)、[V2__m0_query_indexes.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V2__m0_query_indexes.sql)；官方：[Flyway](https://documentation.red-gate.com/flyway)、[Compose](https://docs.docker.com/compose/)
