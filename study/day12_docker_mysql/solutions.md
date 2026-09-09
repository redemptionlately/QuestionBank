# Day12 Docker MySQL · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `docker-compose.yml`、`application.yml`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`docker-compose.yml` 的 mysql service（image/ports/volumes/healthcheck）；`application.yml` 的 datasource、Flyway、JPA 配置；V1/V2 迁移文件。

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

compose 中定义：
```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uquestion_bank", "-pquestion_bank"]
  interval: 5s
  timeout: 5s
  retries: 20
```
启动顺序链路：**容器进程起来 → healthcheck 连续探活通过（healthy）→ 才启动 Java 应用**（多容器时用 `depends_on: condition: service_healthy` 保证）。原因：MySQL 进程可连接 ≠ 已完成初始化，过早连接会被拒绝。

---

### Q4. 应用首次启动成功执行 Flyway V1。
真实连接配置（`application.yml`）：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: question_bank
    password: question_bank
  jpa:
    hibernate:
      ddl-auto: validate      # 不建表，只校验
  flyway:
    enabled: true
```
首次启动：连接宿主 `127.0.0.1:3307`（映射到容器 3306）→ Flyway 按版本执行 `V1__m0_schema.sql、V2__m0_query_indexes.sql、V3__async_import_jobs.sql`，写入 `flyway_schema_history` → Hibernate validate 实体与表一致 → 应用就绪。

---

### Q5. 重启应用不重复执行 V1，已发布数据仍存在。
Flyway 启动时比对 `flyway_schema_history`：已记录且 checksum 一致的版本直接跳过，因此重启不会重复建表。数据保存在命名 volume `question-bank-m0_question_bank_mysql`（挂载到容器 `/var/lib/mysql`），应用重启甚至容器重建都不影响数据；只有 `docker compose down -v` 删除 volume 才会丢数据。

---

### Q6. 解释环境变量覆盖数据库连接配置的方式。
`application.yml` 使用占位符并提供默认值：
```yaml
url: ${DB_URL:jdbc:mysql://localhost:3307/question_bank?...}
username: ${DB_USERNAME:question_bank}
password: ${DB_PASSWORD:question_bank}
```
覆盖优先级（高 → 低）：命令行参数 / 系统环境变量 → application-{profile}.yml → application.yml 默认值。例：
```bash
DB_URL="jdbc:mysql://其他主机:3306/question_bank" DB_USERNAME=u DB_PASSWORD=p mvn spring-boot:run
```
测试环境则由 `application-test.yml` + `@ActiveProfiles("test")` 整体切换到 H2 内存库。

---

## External

### E1. 停止 MySQL 后请求健康检查和业务接口，比较进程存活与依赖就绪。
`docker compose stop mysql` 后：Java 进程仍存活（`/actuator/health` 因数据源指示器变 DOWN 或 DB 相关健康项失败）；业务接口在访问数据库时抛连接异常 → 500 `INTERNAL_ERROR`。说明“进程活着”与“依赖就绪、业务可用”是三个不同层次，不能混为一谈。

### E2. 删除容器但保留 volume，确认数据恢复；明确删除 volume 的不可逆风险。
`docker compose down`（不带 -v）只删容器，命名 volume 保留；再次 `up -d` 后容器重新挂载同一 volume，`flyway_schema_history` 与业务数据都在，Flyway 不会重建表。`down -v` / `docker volume rm` 会删除 `/var/lib/mysql` 全部数据且无法恢复（除非有备份），属于不可逆操作。

### E3. 使用错误数据库密码启动，记录失败阶段和错误信息。
密码错误时应用在**启动阶段获取数据库连接**时失败（HikariCP 报 `Access denied for user ...`），Flyway 无法迁移、上下文启动失败，应用直接退出（还没开始监听端口）。这属于“依赖配置错误”，发生在 Bean 初始化/迁移阶段，而不是请求阶段；修正 `DB_PASSWORD` 后即可启动。
