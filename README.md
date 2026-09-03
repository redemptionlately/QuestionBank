# Question Bank M0

Java 21 + Spring Boot 3.4 + Spring Data JPA + Flyway + MySQL 8 的模块化单体最小业务闭环。

学习路线与长期求职文档的唯一来源是 `/home/allen/study/java/m0/` 和
`/home/allen/AI_Infra/Phases_book/`；项目目录中的旧规划副本不作为当前状态依据。

## Scope

- Admin login, question bank creation, draft paper creation and immutable publishing.
- Student login, published paper browsing, practice draft saving and idempotent submission.
- Deterministic grading for single-choice, multiple-choice and true/false questions.
- Wrong-question aggregation, Flyway migration, unified error responses and Docker Compose MySQL.
- Fixed-window rate limiting (429), process-local TTL cache-aside baseline, atomic request metrics.
- Persistent async import jobs: `RECEIVED -> PROCESSING -> SUCCEEDED/FAILED`, `202 Accepted` plus `Location`,
  and after-commit worker scheduling.

Real PDF extraction, Redis-backed shared cache, MinIO, message brokers, lease recovery, Prometheus registry,
distributed tracing and Agent/Harness remain later increments; their study documents are not implementation evidence.
The local cache is a single-process baseline, the rate limiter is a fixed-window single-process filter,
and `/api/metrics` exposes in-memory counters that reset when the process restarts.

## Run

```bash
cd /home/allen/projects/java-question-bank-m0
docker compose up -d mysql
mvn test
mvn spring-boot:run
```

MySQL is published on `127.0.0.1:3307` (container port `3306`). Override `DB_URL` if another
local port is required.

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
./mvnw -B test
./mvnw -B -DskipTests package
```

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
