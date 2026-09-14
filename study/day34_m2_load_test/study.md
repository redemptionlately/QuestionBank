# MustRemember

- [Loadgen.java](../../tools/loadgen/Loadgen.java)（204 行，单文件 Java 21，`java Loadgen.java` 运行）是本项目真实压测器：**开环**模型——单 producer 线程按 `1_000_000/rps` 固定间隔发请求、不等返回（:54-89），虚拟线程承载响应（:35-38），独立调度器在 warmup 结束置 warmedUp（:92-95），仅 warmup 后样本进统计；报告实测吞吐、错误率与 P50/P95/P99/P999（索引 `ceil(pct/100*n)-1`，:167-173），`--out` 落 JSON
- [seed_papers.sql](../../scripts/sql/seed_papers.sql) 造 5000 行 paper_version（500 发布）并 ANALYZE TABLE；[cleanup_seed.sql](../../scripts/sql/cleanup_seed.sql) 按外键逆序清理；[loadtest.sh](../../scripts/loadtest.sh) 为驱动脚本
- 被测接口：POST /api/auth/login（AuthController:15）、POST /api/practices/{id}/submit（PracticeController:46-47，强制 Idempotency-Key 头）；压测时限流 capacity（application.yml:44，默认 300/min）必须放宽，否则先撞 429
- 吞吐是单位时间完成请求数，响应时间是一次请求从开始到响应的耗时；P50/P95/P99 是排序后的分位点，warmup 用于排除 JIT/连接池/缓存冷启动
- 压测条件必须固定 commit、机器、JDK/JVM 参数、数据库版本与隔离级别、数据量、请求体、并发模型、连接池/线程池、限流配置、持续时间和采样方式
- 同步 API 的"接受任务耗时"与异步任务（ImportJob：提交即返回 RECEIVED，worker 异步完成）的"最终完成耗时"是两条指标链，不能混为一个 latency
- 开环负载按计划速率发请求（高延迟下在途请求堆积），闭环负载按前一个响应返回后再发（高延迟自动减速）；两者在高延迟时产生不同压力
- Little 定律在稳定系统中近似为 `L = λW`：并发中的请求数约等于吞吐 `λ` 乘平均响应时间 `W`；它要求稳定且指标口径一致
- 外部源码索引（MustRemember）：[k6 options](https://grafana.com/docs/k6/latest/using-k6/k6-options/) 的 vus/duration/warmup；[JMeter Thread Group](https://jmeter.apache.org/usermanual/test_plan.html) 的并发模型（项目未使用 k6/JMeter，仅作通用对照）

# MustUnderstand

- 负载接近容量时排队延迟非线性上升；增加线程可能放大数据库锁、连接池等待和上下文切换；开环压测固定到达率，能暴露闭环压测自我减速所掩盖的容量拐点
- 锁等待证据：MysqlRealDbIntegrationTest 的 forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout（:227）、deadlockDetectedAndVictimRolledBack（:259）为 MYSQL_EVIDENCE 门控测试，默认构建跳过；同 session 提交另有 RedisGuardedPracticeSubmitter 应用层锁（app.lock.backend=redis 时）
- 压测结果只绑定当前环境和输入；历史实测数字及其环境绑定表见 docs/interview/resume_one_page.md，严禁脱离环境外推为固定 QPS；生产容量需要真实部署、数据分布、SLO 和故障预算
- 外部源码索引（MustUnderstand）：[k6 metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/) 的 trend/rate/counter 与分位数统计
- 官方：[k6](https://grafana.com/docs/k6/latest/)、[JMeter](https://jmeter.apache.org/usermanual/)
