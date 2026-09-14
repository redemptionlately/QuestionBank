# Day12 Docker MySQL · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 [docker-compose.yml](../../docker-compose.yml)、[application.yml](../../app/src/main/resources/application.yml)。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：根目录 [docker-compose.yml](../../docker-compose.yml) 三个 service——mysql（:2-19，image/ports/volumes/healthcheck）、redis（:21-34，redis:7.4-alpine，宿主 6380→容器 6379，256mb+allkeys-lru）、app（:36-60，build: .，depends_on mysql+redis healthy，APP_CACHE_BACKEND=redis，自身 readiness 探针）；[app/src/main/resources/application.yml](../../app/src/main/resources/application.yml) 的 datasource（:5-8）、Flyway（:16-18）、JPA（:9-15）、MyBatis（:19-23）配置；迁移文件 V1~V4。

---

### Q2. 写出启动 MySQL、查看健康状态、停止服务的 Compose 命令。

```bash
docker compose up -d mysql          # 后台启动 MySQL 8.4 容器
docker compose ps                   # 查看容器状态与 health（starting/healthy）
docker compose logs -f mysql        # 跟踪数据库日志
docker compose exec mysql mysqladmin ping -h localhost -uquestion_bank -pquestion_bank   # 手动探活
docker compose stop mysql           # 停止容器（保留 volume 数据）
docker compose down                 # 停止并删除容器（仍保留命名 volume）
docker compose down -v              # 连 volume 一起删除（数据不可逆清空，慎用）
```

---

### Q3. MySQL healthcheck 通过后启动 Java 应用。

compose 中定义（[docker-compose.yml:15-19](../../docker-compose.yml)）：
```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uquestion_bank", "-pquestion_bank"]
  interval: 5s
  timeout: 5s
  retries: 20
```
启动顺序链路：**容器进程起来 → healthcheck 连续探活通过（healthy）→ 才启动 Java 应用**——app service 的 `depends_on`（:39-43）同时要求 mysql 与 redis 两个 `condition: service_healthy`；app 自身还有 readiness 健康检查（curl `/actuator/health/readiness`，start_period 40s，:55-60）。原因：MySQL 进程可连接 ≠ 已完成初始化，过早连接会被拒绝。

---

### Q4. 应用首次启动成功执行 Flyway V1。
真实连接配置（[app/src/main/resources/application.yml:5-18](../../app/src/main/resources/application.yml)）：
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3307/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
    username: ${DB_USERNAME:question_bank}
    password: ${DB_PASSWORD:question_bank}
  jpa:
    hibernate:
      ddl-auto: validate      # 不建表，只校验（:11）
  flyway:
    enabled: true
    validate-on-migrate: true
```
首次启动：本地直连宿主 `127.0.0.1:3307`（映射到容器 3306；容器内 app 则通过环境变量 DB_URL 连服务名 `mysql:3306`）→ Flyway 按版本执行 V1（七表）、V2（索引）、V3（import_job）、V4（outbox 两表），写入 `flyway_schema_history` → Hibernate validate 实体与表一致 → 应用就绪。真实 MySQL 迁移由 `MysqlRealDbIntegrationTest` 7 个用例（需 MYSQL_EVIDENCE=1 门控）验证。

---

### Q5. 重启应用不重复执行 V1，已发布数据仍存在。
Flyway 启动时比对 `flyway_schema_history`（配合 `validate-on-migrate: true`）：已记录且 checksum 一致的版本直接跳过，因此重启不会重复建表。数据保存在命名 volume `question-bank-m0_question_bank_mysql`（[docker-compose.yml:62-65](../../docker-compose.yml) 显式 `external: true`，挂载到容器 `/var/lib/mysql`），应用重启甚至容器重建都不影响数据；注意 external volume 需要先 `docker volume create question-bank-m0_question_bank_mysql`，且 `docker compose down` 不会删除 external volume，只有显式 `docker volume rm` 才会丢数据（`down -v` 对 external volume 同样不删除）。

---

### Q6. 解释环境变量覆盖数据库连接配置的方式。
[application.yml](../../app/src/main/resources/application.yml) 使用占位符并提供默认值：
```yaml
url: ${DB_URL:jdbc:mysql://localhost:3307/question_bank?...}
username: ${DB_USERNAME:question_bank}
password: ${DB_PASSWORD:question_bank}
```
覆盖优先级（高 → 低）：命令行参数 / 系统环境变量 → application-{profile}.yml → [application.yml](../../app/src/main/resources/application.yml) 默认值。例：
```bash
DB_URL="jdbc:mysql://其他主机:3306/question_bank" DB_USERNAME=u DB_PASSWORD=p mvn spring-boot:run
```
测试环境则由 [application-test.yml](../../app/src/test/resources/application-test.yml) + `@ActiveProfiles("test")` 整体切换到 H2 内存库。

---

## External

### E1. 停止 MySQL 后请求健康检查和业务接口，比较进程存活与依赖就绪。
`docker compose stop mysql` 后：Java 进程仍存活，但 `/actuator/health` 因数据源指示器与自研 `DatabaseLatencyHealthIndicator`（SELECT 1 探测失败）变 DOWN（HTTP 503）；业务接口在访问数据库时抛连接异常 → 500 `INTERNAL_ERROR`。说明“进程活着”与“依赖就绪、业务可用”是三个不同层次，不能混为一谈。

### E2. 删除容器但保留 volume，确认数据恢复；明确删除 volume 的不可逆风险。
`docker compose down` 只删容器，命名 volume 保留（本项目 volume 声明为 `external: true`，连 `down -v` 也不会删除它）；再次 `up -d` 后容器重新挂载同一 volume，`flyway_schema_history` 与业务数据都在，Flyway 不会重建表。真正清空数据需要显式 `docker volume rm question-bank-m0_question_bank_mysql`，它会删除 `/var/lib/mysql` 全部数据且无法恢复（除非有备份），属于不可逆操作。

### E3. 使用错误数据库密码启动，记录失败阶段和错误信息。
密码错误时应用在**启动阶段获取数据库连接**时失败（HikariCP 报 `Access denied for user ...`），Flyway 无法迁移、上下文启动失败，应用直接退出（还没开始监听端口）。这属于“依赖配置错误”，发生在 Bean 初始化/迁移阶段，而不是请求阶段；修正 `DB_PASSWORD` 后即可启动。
