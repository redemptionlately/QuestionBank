# Question Bank M0

Java 21 + Spring Boot 3.4 + Spring Data JPA + Flyway + MySQL 8 的模块化单体最小业务闭环。

学习路线与长期求职文档的唯一来源是 `study/` 与 `docs/`；项目目录中的旧规划副本不作为当前状态依据。
Windows Git Bash 下 `mvnw` 无法启动，构建一律走 `./scripts/mvn.sh`（见下文）。

## Scope

- Admin login, question bank creation, draft paper creation and immutable publishing.
- Student login, published paper browsing, practice draft saving and idempotent submission.
- Deterministic grading for single-choice, multiple-choice and true/false questions.
- Wrong-question aggregation, Flyway migration, unified error responses and Docker Compose MySQL.
- Fixed-window rate limiting (429), process-local TTL cache-aside baseline, atomic request metrics.
- Persistent async import jobs: `RECEIVED -> PROCESSING -> SUCCEEDED/FAILED`, `202 Accepted` plus `Location`,
  and after-commit worker scheduling.

Current state (all evidence-verified; the full capability matrix with rerun commands lives in
`docs/JAVA_BACKEND_COVERAGE.md`, unfinished items are listed there under「仍未接入或未验证」):
Redis shared cache / distributed lock / token-bucket rate limiting (switchable backends via
`app.cache.backend` / `app.lock.backend` / `app.rate-limit.backend`, local fallback preserved),
Kafka outbox + DLT + 3-broker HA, MySQL read/write splitting, ShardingSphere sharding,
Prometheus registry (`/actuator/prometheus`), Spring Cloud split (Eureka/Gateway/Feign/Resilience4j, `cloud/`),
Micrometer Tracing + OTLP, K8s kind deploy, custom Spring Boot starter, ArchUnit guards, PIT mutation testing.
Real PDF extraction, MinIO, lease recovery and Elasticsearch remain later increments.
The custom `/api/metrics` endpoint still exposes in-memory counters that reset when the process restarts.

## Run

```bash
# 前提：本地 MySQL 9 已在 127.0.0.1:3306 运行，库 question_bank 已建
./scripts/mvn.sh -B clean verify          # 编译 + 127 个测试 + 覆盖率门禁（0.80/0.65/0.80）
./scripts/mvn.sh spring-boot:run          # 需要 DB_URL/DB_USERNAME/DB_PASSWORD 环境变量
docker compose up -d mysql redis app      # 容器化路径（本机未装 Docker，未经实跑验证）
```

默认数据源指向 `127.0.0.1:3307`（Compose 端口）。本机 MySQL 在 3306，启动时覆盖：

```bash
export DB_URL="jdbc:mysql://127.0.0.1:3306/question_bank?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_USERNAME=root
export DB_PASSWORD=你的密码
```

Development users are seeded for local learning only:

```text
admin / admin123
student / student123
```

The first login returns a bearer token. Use it as `Authorization: Bearer <token>`.

## Main API

- `POST /api/auth/login`
- `POST /api/admin/banks`
- `POST /api/admin/banks/{bankId}/versions`
- `POST /api/admin/versions/{paperId}/publish`
- `GET /api/papers/published`
- `POST /api/practices`
- `PUT /api/practices/{sessionId}/answers/{questionId}`
- `POST /api/practices/{sessionId}/submit` with `Idempotency-Key`
- `GET /api/wrong-questions`
- `POST /api/import-jobs` and `GET /api/import-jobs/{id}`
- `GET /api/metrics`

## Verification

Run the focused test suite and package build:

```bash
./scripts/mvn.sh -B clean verify
./scripts/mvn.sh -B -DskipTests package
```

### 证据脚本（产出落在 `output/`，面试用原始日志）

```bash
MYSQL_EVIDENCE=true ./scripts/mysql-evidence.sh   # 真实 MySQL：EXPLAIN / 隔离级别 / 行锁
REDIS_EVIDENCE=true MYSQL_EVIDENCE=true KAFKA_EVIDENCE=true \
  MYSQL_SHARDING_EVIDENCE=true ./scripts/mvn.sh -B clean verify   # 全量门禁：127 个测试 0 失败（2026-09-09 实测）
./scripts/loadtest.sh                             # 开环压测 + JFR，需要 MySQL 与空闲 8080 端口
```

- Redis 共享缓存默认关闭，通过 `app.cache.backend=redis` 开启，默认 `local` 可一键回退。
- 压测前脚本会把限流放宽到 1,000,000/min，否则测到的是限流器而不是业务容量。
- 本机 `jcmd` attach 会「拒绝访问」，JFR 用 `-XX:StartFlightRecording` 启动参数采集。

The tests cover all three question types, immutable submitted practices, malformed requests,
authorization, draft transaction rollback, query-index migration, and same-key concurrent submit.
Submission takes a database row-level write lock, so the persisted idempotency result is stable across
application threads and instances. M0 does not yet include idempotency-record expiry or a separate
distributed idempotency store.

For a real MySQL plan check after starting Compose:

```bash
docker compose exec -T mysql mysql -uquestion_bank -pquestion_bank question_bank \
  -e "EXPLAIN SELECT id, title FROM paper_version WHERE status='PUBLISHED' ORDER BY published_at DESC;"
```

The plan should show `idx_paper_version_status_published_at` in the `key` column once the table has
enough rows for the optimizer to choose the index. Record the output in `output/` with `tee` when
using it as interview evidence.

## Evidence

The source code is not proof of user mastery. Run `mvn test`, inspect the migration and explain the
publish/submit transaction boundaries before calling M0 verified or learned.
