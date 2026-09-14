# MustRemember

- Compose 中 `image` 是模板，`container` 是实例，`volume` 保存容器外持久数据，`ports` 做宿主/容器端口映射，`healthcheck` 定义可观测探针
- M0 的端口是 MySQL 宿主 `127.0.0.1:3307 -> container:3306`、Redis 宿主 `6380 -> container:6379`、app `8080 -> 8080`；应用连接串由 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量覆盖，缓存后端由 `APP_CACHE_BACKEND` 切换（容器内=redis，本地默认=local）
- 启动依赖顺序是 mysql+redis 双 healthy（depends_on condition: service_healthy）-> Flyway 应用 V1~V4 -> Hibernate `ddl-auto=validate` 校验实体映射 -> Spring Boot 提供请求
- Compose 服务骨架是：
  ```yaml
  services:
    mysql:
      image: mysql:8.4
      ports: ["127.0.0.1:3307:3306"]
      volumes: [question_bank_mysql:/var/lib/mysql]
      healthcheck:
        test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uquestion_bank", "-pquestion_bank"]
  ```
- 源码索引（MustRemember）：[docker-compose.yml](../../docker-compose.yml) 的 mysql（:2-19）/redis（:21-34）/app（:36-60）三个 service 与 external volume（:62-65）；[application.yml](../../app/src/main/resources/application.yml) 第 5-23 行的 datasource/Flyway/JPA/MyBatis 配置

# MustUnderstand

- `healthcheck` 不等于业务 E2E；Flyway schema history 记录版本和 checksum，防止每次启动重复建表
- 删除 container 通常保留 volume；删除 volume 会删除数据库持久状态，容器健康与数据恢复是两个概念
- 源码索引（MustUnderstand）：[docker-compose.yml](../../docker-compose.yml) 第 39-43 行的 `depends_on`/health 依赖、第 55-60 行 app 自身 readiness 探针；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 至 [V4__outbox_events.sql](../../app/src/main/resources/db/migration/V4__outbox_events.sql) 的迁移顺序
- 关键配置：[docker-compose.yml](../../docker-compose.yml)、[application.yml](../../app/src/main/resources/application.yml)；官方：[Compose](https://docs.docker.com/compose/)、[MySQL Image](https://hub.docker.com/_/mysql)
