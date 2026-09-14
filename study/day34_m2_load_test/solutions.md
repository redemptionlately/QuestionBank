# Day34 M2 Load Test · 题目与标准解答（Solutions）

> 依据：`study.md` + 项目真实压测器 [tools/loadgen/Loadgen.java](../../tools/loadgen/Loadgen.java)（单文件 Java 21 开环压测器）、种子 SQL [scripts/sql/seed_papers.sql](../../scripts/sql/seed_papers.sql)、驱动脚本 [scripts/loadtest.sh](../../scripts/loadtest.sh)，以及真实接口 `POST /api/auth/login`、`POST /api/practices/{id}/submit`。k6/JMeter 仅作为外部通用工具知识保留。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
本主题压测方法论的真实锚点：
- [tools/loadgen/Loadgen.java](../../tools/loadgen/Loadgen.java)（204 行，单文件源码，`java Loadgen.java` 直接运行，无 Maven 坐标）：参数 Args 在 :175-183，默认 url=`http://127.0.0.1:8080/api/papers/published`、method=GET、rps=100、warmup=10s、duration=60s；`--url/--method/--body/--token-file/--rps/--warmup/--duration/--out`（:185-201）。输入：命令行参数 + 可选 token 文件/请求体；输出：控制台报告与 `--out` JSON（目标 RPS、实测 RPS、样本数、错误数/率、P50/P95/P99/P999、Max）。
- [scripts/sql/seed_papers.sql](../../scripts/sql/seed_papers.sql)：递归 CTE 向 paper_version 插 5000 行（500 行 PUBLISHED），`ANALYZE TABLE` 刷新统计；清理用 [scripts/sql/cleanup_seed.sql](../../scripts/sql/cleanup_seed.sql)（按外键逆序 6 步删除）。
- 被测真实接口：`POST /api/auth/login`（AuthController:15，permitAll）、`POST /api/practices/{sessionId}/submit`（PracticeController:46-47，需 Bearer token 且强制 `Idempotency-Key` 头）。
- 配套配置：[application.yml:44-45](../../app/src/main/resources/application.yml) 限流 capacity=300/window=PT1M（压测时需放宽，否则先撞 429 而非容量上限）；主数据源未显式设池大小（HikariCP 默认 maximumPoolSize=10）；`read-replica` profile 的 `ReadReplicaDataSourceConfig.hikariOf`（:57-67）显式 maxPoolSize=5/minIdle=1/connectionTimeout=5000ms。

---

### Q2. 为登录/提交写变量表和停止条件，保存原始日志。
**变量表（压测前固定）**：
| 变量 | 登录 | 提交 |
|---|---|---|
| 目标 RPS | Loadgen `--rps` 固定值（开环到达率） | 同左 |
| 凭据/Token | 预置 user_account 账号；登录后 token 写入 `--token-file target/token.txt`，Loadgen buildRequest（:106-119）自动加 `Authorization: Bearer` | 每用户预建 IN_PROGRESS 会话 |
| 请求体 | `--body` 传 username/password JSON | `Idempotency-Key` 每请求唯一（前端用 crypto.randomUUID 存 sessionStorage，压测脚本同样每请求生成） |
| 数据量 | user_account 行数固定 | [seed_papers.sql](../../scripts/sql/seed_papers.sql) 5000 版本/500 发布固定 |
| 断言 | 200 且返回 token | 200 且 totalScore 正确、同幂等键重放结果稳定 |

**停止条件**：错误率超过阈值（如 >1%）、P95 超过 SLO、连接池等待持续上升、出现 5xx 即停止并保存现场。
**保存原始日志**：Loadgen 用 `--out out.json` 落逐字段原始报告，外层 `scripts/loadtest.sh ... 2>&1 | Tee-Object output/load_YYYYMMDD.log`（PowerShell `Tee-Object`，bash 用 tee）留存完整控制台；原始结果不可只留汇总数字。

---

### Q3. 写出吞吐、响应时间、P50/P95/P99、warmup、并发、连接池定义。
- **吞吐（Throughput, λ）**：单位时间完成的请求数（RPS/QPS）；Loadgen report（:121-165）的实测吞吐 = warmup 后样本数 / 实际测量秒数（不是目标 rps）；
- **响应时间（Latency, W）**：单请求从发出到收到响应的耗时；Loadgen 样本转 ms 后升序排序；
- **P50/P95/P99/P999**：排序后位于对应百分位的值；Loadgen 索引公式 `ceil(pct/100*n)-1`（:167-173），另输出 Max；
- **warmup（预热）**：先跑一段不计入结果的负载，排除 JIT、连接池建立、缓存冷启动；Loadgen 由独立 ScheduledExecutorService 在 warmup 结束时把 `warmedUp` 置 true（:92-95），**只有 warmup 后的样本进统计**；
- **并发（concurrency, L）**：同一时刻在处理中的请求数；开环下它是被测系统的**结果**而非控制量；
- **数据库连接池**：应用到 DB 的连接上限，并发数据库操作超过池大小时请求排队等连接（HikariCP maximumPoolSize、connectionTimeout）。

---

### Q4. 用 Little 定律计算平均并发。
稳定系统中 `L = λ × W`（并发数 ≈ 吞吐 × 平均响应时间，单位统一为秒）。
例：目标吞吐 λ=200 RPS，平均响应 W=50ms=0.05s，则平均在途请求 L=200×0.05=**10**；
反推：若连接池只有 10、平均 W=0.05s，则数据库侧吞吐上限约 10/0.05=200 RPS。
前提：系统稳定（到达率≈完成率）、指标口径一致；它是近似关系，不覆盖排队突增。开环压测（Loadgen）的价值正是：到达率固定不变，当系统能力不足时 L（在途数）会持续堆积而非像闭环那样自动减速，从而暴露 λ×W 失稳的拐点。

---

### Q5. 区分开环/闭环压测，列出固定环境字段。
- **闭环（closed-loop，如 JMeter 线程组/k6 常见 VU）**：一个虚拟用户发完、收到响应后才发下一个；延迟升高时它自然减速，压力受 VU 数限制；
- **开环（open-loop，本项目 Loadgen 的真实实现）**：单个 daemon producer 线程（:54-89）按固定到达间隔 `periodMicros = 1_000_000 / rps` 发请求，**不等上一个返回**；响应承载在 `Executors.newVirtualThreadPerTaskExecutor()`（:35-38，虚拟线程）；睡眠粒度封顶 5ms，落后超过 1s 则重置节拍（:67-69，避免停机后爆发补发）；请求级超时 10s（buildRequest :106-119）；status=0（异常）或 ≥400 计错误，运行中 failed 只计 ≥500。
**必须固定的环境字段**：代码 commit、机器规格、JDK 与 JVM 参数（堆大小/GC，如 -Xms512m -Xmx1g、MaxGCPauseMillis）、数据库版本与隔离级别、数据量与分布（5000/500）、请求体、并发模型（开/闭环、RPS）、连接池/线程池大小、限流配置（压测放宽 capacity）、warmup 与测量时长、采样方式。任一变化结果不可直接比较；历史实测数字只绑定其记录环境（见 `docs/interview/resume_one_page.md` 的压测环境绑定表），不得外推为通用 QPS。

---

## External

### E1. 比较连接池 / 线程池。
- Tomcat 线程池决定同时处理多少 HTTP 请求；DB 连接池决定同时有多少请求能访问数据库；
- 当请求都要查库时，**瓶颈取两者较小者**：线程多但连接池小，多余线程阻塞等连接；盲目增大线程只会增加上下文切换与连接等待；
- 压测时分别观察：Actuator `/actuator/metrics` 与 `/actuator/prometheus` 暴露的 Tomcat/HikariCP Gauge（活跃线程、在用/空闲连接、等待时长），配合 JFR 定位是 CPU、锁还是连接受限。

### E2. 观察锁等待。
对提交接口加压（同一 session 会争用 `PracticeService.submit` 的 SELECT…FOR UPDATE 行锁）：用 `SHOW ENGINE INNODB STATUS`、`information_schema.innodb_trx` 观察锁等待，结合应用 P95 上升，说明瓶颈从 CPU 转移到数据库锁。项目内对应的真 MySQL 证据测试（门控 MYSQL_EVIDENCE，默认构建跳过）：`MysqlRealDbIntegrationTest.forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout`（:227，行锁等待超时）与 `deadlockDetectedAndVictimRolledBack`（:259，死锁检测+牺牲者回滚）。不同 session 之间不应争同一行锁，可据此区分"热行竞争"与"整体容量不足"；`app.lock.backend=redis` 时还有 RedisGuardedPracticeSubmitter 在应用层先串行化同 session 提交（锁 TTL 10s）。压测结论只绑定当前环境与数据量，不外推为固定 QPS。
