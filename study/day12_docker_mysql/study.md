# 必须会背会写

- Compose 中 `image` 是模板，`container` 是实例，`volume` 保存容器外持久数据，`ports` 做宿主/容器端口映射，`healthcheck` 定义可观测探针
- M0 的端口是宿主 `127.0.0.1:3307 -> container:3306`；应用连接串由 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量覆盖
- 启动依赖顺序是数据库可连接 -> Flyway 应用 V1/V2 -> Hibernate `ddl-auto=validate` 校验实体映射 -> Spring Boot 提供请求
- Compose 服务骨架是：
  ```yaml
  services:
    mysql:
      image: mysql:8.4
      ports: ["127.0.0.1:3307:3306"]
      volumes: [question_bank_mysql:/var/lib/mysql]
      healthcheck:
        test: ["CMD-SHELL", "mysqladmin ping -h localhost -uquestion_bank -pquestion_bank"]
  ```
- 源码索引（会背会写）：[docker-compose.yml](../../../../projects/java-question-bank-m0/docker-compose.yml) 的 mysql service、ports、volume、healthcheck 字段；[application.yml](../../../../projects/java-question-bank-m0/src/main/resources/application.yml) 的 datasource/Flyway/JPA 配置

# 必须理解

- `healthcheck` 不等于业务 E2E；Flyway schema history 记录版本和 checksum，防止每次启动重复建表
- 删除 container 通常保留 volume；删除 volume 会删除数据库持久状态，容器健康与数据恢复是两个概念
- 源码索引（必须理解）：[docker-compose.yml](../../../../projects/java-question-bank-m0/docker-compose.yml) 的 `depends_on`/health 依赖；[V1__m0_schema.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V1__m0_schema.sql) 与 [V2__m0_query_indexes.sql](../../../../projects/java-question-bank-m0/src/main/resources/db/migration/V2__m0_query_indexes.sql) 的迁移顺序
- 关键配置：[docker-compose.yml](../../../../projects/java-question-bank-m0/docker-compose.yml)、[application.yml](../../../../projects/java-question-bank-m0/src/main/resources/application.yml)；官方：[Compose](https://docs.docker.com/compose/)、[MySQL Image](https://hub.docker.com/_/mysql)
