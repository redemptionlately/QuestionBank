# 项目一页描述（每条都可指向证据）

**题库与在线练习平台 · 模块化单体 + Spring Cloud 拆分** ｜ Java 21 · Spring Boot 3.4 · Spring Cloud 2024 · Spring Data JPA · MyBatis · Kafka · Flyway · MySQL 9.0 · Redis 8.10 · Kubernetes

---

## 项目描述

基于 Spring Boot 3.4 / Java 21 实现题库管理与在线练习闭环：建库 → 追加式版本发布 → 练习保存 → 确定性判分 → 幂等提交 → 错题归集。
无状态 Bearer 认证 + 角色授权 + 资源归属校验，统一错误响应与 requestId 链路追踪。

---

## 可写进简历的十一条（每条后面是证据）

**1 · 提交幂等：Redis 锁减少冲突，数据库行锁 + 唯一键保证正确**
`app.lock.backend=redis` 时，提交先取分布式锁——**锁在事务外获取、事务提交后释放**（在事务内持锁会给
别的实例留出读到未提交状态的窗口）；Lua 校验 token 防误删，Redis INCR 生成单调 fencing token。
并发 4 路实测：1 成功 + 3 显式拒绝；**故意绕过锁后，数据库行锁 + 幂等键仍返回完全一致的结果**——
锁可以失效，正确性不能。
> 证据：`RedisGuardedSubmitIntegrationTest`（两条用例输出）、`RedisLockAndRateLimitIntegrationTest`
> （互斥/误删防护/TTL 自动释放/fencing 单调）、`MysqlRealDbIntegrationTest#forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout`。

**2 · 索引与执行计划：命中与失效都有实测对照**
5000 行数据下，`WHERE status='PUBLISHED' ORDER BY published_at DESC` 命中
`idx_paper_version_status_published_at`，`Extra=Backward index scan`（无 filesort）；
把谓词改成 `UPPER(status)=...` 后 `type=ALL, key=NULL, rows=9640, Using filesort`。
> 证据：`output/mysql_evidence_*.log`，`scripts/sql/seed_papers.sql` 可复现。

**3 · 事务与隔离级别：不是背概念，是跑出来的**
真实 MySQL 9.0（默认 REPEATABLE-READ）上验证：RR 下同一事务两次读返回快照值，提交后才见新值；
切到 READ COMMITTED 后第二次读立即可见新提交值；`REQUIRES_NEW` 内层独立提交，外层回滚不影响它。
**死锁实测**：两事务交叉加锁成环，InnoDB 检测器即时介入——牺牲者收 SQLSTATE 40001 / errorCode 1213
并回滚，幸存事务正常提交；`SHOW ENGINE INNODB STATUS` 的 LATEST DETECTED DEADLOCK 段落完整展示
双方 RECORD LOCKS。
**事务失效五条路径逐一实测**：自调用绕过代理（注入引用是代理、方法内 this 是目标对象）= 回滚失效
逐句自动提交；异常被吞 = 照常提交；受检异常默认不回滚；rollbackFor 纳入回滚范围。
> 证据：`MysqlRealDbIntegrationTest` 隔离/传播/死锁用例（`output/mysql_deadlock_evidence.log`）、
> `TransactionFailureModeTest` 五条 [evidence] 输出。

**4 · Redis 共享缓存：cache-aside + 事务提交后再淘汰 + 故障降级**
`app.cache.backend` 一键切换本地缓存 / Redis；TTL 带抖动避免同时过期；
发布操作的缓存淘汰放在 `afterCommit`（避免事务未提交就被别的请求写回旧值）；
Redis 宕机时降级到数据库而不是 500。
> 证据：`RedisCacheIntegrationTest` 三个用例（命中率、提交后淘汰、宕机降级）。

**5 · Outbox + Kafka + 消费幂等：不丢不重的落地方案，端到端实测**
发布事务内同步落库 `outbox_event`（消息本体就在库里，"改了库但消息丢了"不可能发生）；
投递器轮询发送，成功才标 SENT，失败 requeue 直至 maxAttempts 停 FAILED 等人工介入；
消费端按 eventKey 唯一键幂等去重——投递是"至少一次"，消费保证"不重"，合起来才是不丢不重。
Kafka 4.0（KRaft 单机）端到端实测：发布 API → outbox 落库 → 投递器发 Kafka → consumed_event 记录 →
同一 eventKey 手动重投，幂等表不增第二条。单元测试曾抓出真 bug：序列化为 null 时旧代码会静默落空事件。
> 证据：`OutboxTest`（7 用例）+ `OutboxRelayAndConsumerTest`（5 用例）+ `KafkaOutboxIntegrationTest`
> （`output/kafka_evidence_*.log`，`scripts/kafka-evidence.sh` 一键复现）。
**高可用实测**：3 节点 KRaft 集群 + RF=3/min.isr=2 主题，kill -9 一个 broker 后仲裁仍可用、
ISR 收缩、三分区 leader 全部自动迁移，故障期继续投递，消费端唯一消息 150/150 零丢失。
> 证据：`output/kafka_cluster_ha3_final.log`。

**6 · 覆盖率 + 变异测试：门禁挡构建，变异分数挡假测试**
Jacoco 挂在 verify 阶段：指令 80% / 分支 55% / 行 80%（当前实测 86.3% / 66.1% / 90.6%，app 94 测试 + starter 3 测试 0 失败 0 跳过）；
PIT 变异测试对 event 包实测测试强度 91%——覆盖率说代码被执行过，变异分数说断言真能挡住错误。
变异测试驱动补强 4 处断言缺口，并抓出一个真缺陷（校验异常写进 try 块导致两种失败路径被 catch 坍缩，
已重构修复并区分失败语义）。
GitHub Actions 全绿（mysql:9.0 / redis:7 / kafka:4.0.0 三服务容器 + 全量测试 + 门禁 + 打包 + 镜像构建冒烟）：
覆盖率门禁两次真实挡下构建（一次脚本丢失执行位、一次证据测试被跳过稀释至 74%），
都是修根因而不是调阈值——门禁不是摆设。
> 证据：`target/site/jacoco/index.html`、`output/pit_*.log`、`target/pit-reports/`、
> [CI run 34117813609](https://github.com/redemptionlately/QuestionBank/actions/runs/34117813609)。

**6.5 · MyBatis 与 JPA 双栈 + 完整性能调优闭环**
复杂读查询（条件全可选 + 分页 + 聚合）用 MyBatis 动态 SQL 接管：结果与手写 SQL 逐行一致（真实 MySQL 对照）、
二级缓存 LRU/TTL 命中用 StatementHandler 层拦截计数器实证"第二次没打数据库"；
聚合缓存 60s TTL 是刻意的弱一致窗口（JPA 写入 MyBatis 感知不到）——测试用独立用户隔离缓存 key 正是绕开这个坑。
调优闭环：JFR 实锤 BCrypt 占 login 84% → cost 三档实测（52.65 / 15.5 / 4.15 RPS，cost 翻倍吞吐减半）→
保持 cost=10 横向扩容而不是降安全余量；G1 vs ZGC 对照证明瓶颈不在 GC 时换收集器无用。
> 证据：`WrongQuestionQueryMapperTest`、`MysqlRealDbIntegrationTest`（MyBatis 对照）、
> `output/bcrypt_strength_*.log`、[`performance_tuning_report.md`](../performance_tuning_report.md)。

**7 · 性能有原始压测数据，不是"应该很快"**
开环压测（虚拟线程，warmup 后统计），5000 份试卷 / 500 份已发布，32C32G 本机 + 同机 MySQL 9.0.1，
HikariCP 最大 10 连接：
- `GET /api/papers/published` @100 RPS：实测 100.0 RPS，**P50 2ms / P95 5ms / P99 5-10ms**，错误率 0%
- `POST /api/auth/login` @20 RPS：实测 **15.6-16.1 RPS**（BCrypt 打满，吞吐上不去），P50 61ms / P95 65ms / P99 76ms
- 同窗口 GC 行为（G1, -Xms512m -Xmx1g）：23 次停顿共 147ms，吞吐 99.9%，**Full GC 0 次**，
  停顿 P50 6.3ms / MAX 17.2ms（目标 200ms）——延迟不受 GC 支配
- **JFR profile 采样：BCrypt.key 占执行 84.01%**——登录瓶颈是 profiler 数据不是猜测；
  ZGC 分代对照：停顿 MAX 0.027ms（G1 17.2ms 的 1/600），但应用 P99 不变——瓶颈不在 GC，换收集器不解决延迟
> 证据：`output/load_*.json`、`output/load_*.jfr`、`output/jfr_analysis_*.log`、
> `output/gc_report_*.log`（`scripts/gc-report.sh` / `scripts/gc-compare-zgc.sh` 一键复现）。

**8 · 架构治理与部署：ArchUnit 规则化 + 自研 Starter + 读写分离 + K8s**
ArchUnit 5 条规则进 CI（分层依赖方向 / 禁字段注入 / 禁 System.out），上岗首跑抓出 2 处真实反向依赖
（common→auth、common→bank）并重构修复——规则是可执行约束不是文档；
慢 SQL 监控抽成自研 monitor-spring-boot-starter（Maven 多模块：@AutoConfiguration + 条件装配 +
imports 注册，ApplicationContextRunner 条件矩阵 3 用例），业务方单依赖接入、starter 可独立发布；
MySQL 9 GTID 主从 + AbstractRoutingDataSource 路由（LazyConnectionDataSourceProxy 把取连接推迟到首条 SQL），
SELECT @@port 实证 readOnly→从库 / 写→主库，super_read_only 硬拒错误方向写；
CI 内 kind 集群部署（Deployment/Service/Secret/HPA CPU70% 1→3），探针与 HPA 真实指标断言全绿。
> 证据：`ArchitectureTest`、`monitor-starter/`（独立模块 3 用例）、
> `ReadReplicaRoutingH2Test` / `ReadReplicaRoutingIntegrationTest`（SELECT @@port 铁证）、
> `deploy/k8s/` + CI k8s-deploy job（kind 全链路全绿）。

**9 · Java 核心深度：AQS 与类加载不是背的，是写出来跑出来的**
手写 AQS 独占可重入锁 + 共享模式信号量，与 JDK `ReentrantLock` 三方对照：8×20000 自增
**无锁 28437 / JDK 锁 160000 / 自研锁 160000**（无锁丢 82% 更新，对照组不是装饰）；可重入计数必须与
释放次数严格配对（获取 3 次只释放 2 次 → 等待线程仍被阻塞，补全释放后立刻获得锁）；非持有者释放抛
`IllegalMonitorStateException`；`await()` 期间锁可被他人获取（实证 await 真的释放了锁，否则无人能 signal）。
类加载：自定义加载器请求 `java.lang.String` → 由 bootstrap 提供（`getClassLoader()`=null）= 双亲委派实证；
**同名类被两个加载器定义就是两个不同的 Class，互转 `ClassCastException`**（类身份 = 类本身 + 加载器）；
重写 `loadClass` 先自己 define 即可绕过父加载器（委派是约定不是强制）；TCCL 让父加载器的代码
"向下"看到子加载器的类（SPI 的机制基础）。
> 证据：`AqsEvidenceTest`（6 用例）、`ClassLoaderEvidenceTest`（4 用例）。
> 附一条被失败用例纠正的认知：可重入锁的 `tryLock()` 对**持有者自己**是重入成功返回 true
> （与 JDK 一致），只有对**其他线程**持锁时才立即返回 false——这条我最初写错了，是测试纠正的。

**10 · Spring Cloud 微服务拆分：注册/网关/Feign/熔断全部真机取证，不是 demo 演示**
按数据所有权拆出 auth/bank/practice 三服务，各自独立 MySQL schema（SHOW TABLES 实证三库表集合互不相交）；
Eureka 注册发现 + Gateway 统一入口（无 token 401 / 伪造 401 / 绕过网关直连被服务自身拒绝；
验签服务不可达时网关 503 分流——"门禁坏了只能关门"，和 token 无效的 401 是两回事）；
Redis 令牌桶限流（单 curl 进程 10 连接同刻并发突发实测 **200×5 + 429×5**）；
OpenFeign 契约模块跨服务调用；Resilience4j 熔断实测全周期：杀掉 bank → 6 次调用全 503 快速失败、
状态 OPEN → 重启后服务发现 2s 重新收敛 → 半开探测通过 → CLOSED 自动恢复。
熔断有意义的前提是先做了数据所有权设计：practice 建会话时把试卷快照复制进自己的库，判分是纯本地事务——
bank 挂掉只挡新会话，不挡已开会话的提交，降级才是真的降级而不是摆设。
发布链路保留事务性发件箱：outbox_event 与业务同事务落库，轮询投递 Kafka，消费端按 eventKey 唯一键幂等。
链路追踪同标准取证：四服务接入 Micrometer Tracing（Observation API 经 OTel bridge 落 OTel SDK，
OTLP/HTTP 导出、otelcol debug exporter 把每个 span 原样落盘），5 项证据一键复跑：
跨 4 服务同一 traceId（学生建会话 7 span：网关 → 验签 auth → 路由 practice → Feign → bank 快照全链一树）、
跨服务 parent 链闭合、Feign 传播、401 错误路径留痕、日志 traceId 反查 collector。
三个教科书不写的坑各留原始日志：①@LoadBalanced builder 被 Spring Cloud 以同名 bean 覆盖 Boot 的
prototype builder——LB 过滤器在而 ObservationWebClientCustomizer 从未生效，验签 span 全成孤立根，
修法是链式补 observationRegistry（而手动再挂 LB 过滤器 = 双重解析、把实例 IP 当服务名 503，也实测踩过）；
②Eureka 首次注册默认 40s + 网关 LB 缓存刷新 35s 的时序竞争打出 503 No servers available；
③span 批量异步导出，断言必须轮询到"多服务同 trace"信号，固定 sleep 撞上批次未齐会假阴性。
两个教科书不会写的真坑都留了原始日志：①限流突发必须单 curl 进程 10 连接同刻并发——逐个 fork 后台进程
会散布 2 秒以上，令牌 2/s 补充恰好追平消耗，测出 9×200 的假结论；②"杀服务"的唯一成功判据是端口真正释放——
JVM shutdown hook 慢死 + Windows taskkill 参数在 Git Bash 下原样透传，6 次熔断调用会全打在垂死实例上。
> 证据：`cloud/`（六模块独立 Maven 聚合，CI 有独立编译门禁 job）、
> `scripts/cloud-evidence.sh` 一键复跑 10 项 [evidence]、`output/cloud_evidence_console.log`；
> 链路追踪：`scripts/tracing-evidence.sh` 复跑 5 项 [evidence]、`output/tracing_collector.log`（原始 span 文本落盘可复核）。

**11 · 分库分表：ShardingSphere 真机取证，路由下推到唯一键边界全部可复现**
ShardingSphere-JDBC 5.5.2，2 库 × 2 表拓扑（practice_session + submission_item 各 4 物理表），
student_id INLINE 双层一致路由；纯 JDBC 独立 DataSource 取证（主线仍 JPA 单库零影响——能力验证与生产引入是两回事）。
六项证据直查物理库：①8 行按分片键精确落位，错位组合 0 行的叉积断言 ②带分片键查询只下发 1 个物理表
（`Actual SQL: ds_1 ::: ... practice_session_1 ...` 铁证）③无路由条件 GROUP BY 广播 4 物理表归并 8 组
④广播表一次写入 2 库各 1 份 + SNOWFLAKE 主键全局唯一（自增 IDENTITY 在分片下只保证单表唯一，物理表去 AUTO_INCREMENT）
⑤bindingTables 把父子 join 从 2×2 笛卡尔积压成 1 条对齐 Actual SQL ⑥唯一键边界如实固化：同分片内 uk 拒绝重复提交，
跨分片同业务键物理不拦——分片后业务唯一性必须把分片键纳入唯一键。
schema 三处改造：主键去自增 / FK 移除（跨库不可达，应用层保证归属）/ 子表冗余分片键（免 join 即可路由）。
SS 5.5 三个教科书不写的坑都有字节码+官方文档实锤：①数据源 YAML 平铺格式（旧 `props:` 嵌套在 5.5.2 被静默忽略，
症状 `Access denied for 'OS用户'@'localhost'` 或 StorageUnit NPE，javap 反编译 swapper 定位）②
`shardingsphere-jdbc` 的 Maven 依赖图不含 pool-hikari（缺它则 PoolMetaData SPI 加载失败 → 标准 props 全部静默丢弃）
③MOD/HASH_MOD 属自动分片算法仅限 autoTables，手写 actualDataNodes 必须用 INLINE。
> 证据：`ShardingEvidenceTest`（6 用例）、`scripts/sharding-evidence.sh` 一键复跑、`output/sharding_evidence_*.log`。

---

## 明确不写（未接入 / 未验证）

- Redis 集群、哨兵、Redlock（当前单机 Redis）
- Kafka 跨机房容灾、事务消息（本机 3 节点集群已实测选主/ISR/零丢失；生产级跨机特性未测）
- Elasticsearch、`search_after` 深分页（未接入）
- Spring Cloud Config/Nacos 配置中心、舱壁隔离 Bulkhead、
  cloud/ 微服务多实例水平扩容实测（拆分与链路追踪均已落地真机证据：拆分 10 项、追踪 5 项；以上组件未接入、未测，不写）
- 服务网格、OAuth2/OIDC（Kubernetes 部署已由 CI kind 集群闭环；生产级集群运维未涉及；~~分库分表~~
  已由 ShardingSphere 真机 6 项证据闭环（2026-09-08），见第 11 条；扩容迁移（双写/影子表）未实施不写）
- 任何"QPS 提升 X 倍"的对外数字：本机单实例、同机数据库，结论不外推到生产

---

## 压测环境字段（结论必须绑定这些，任一变化结果不可比）

| 字段 | 值 |
|---|---|
| 机器 | 32 核 / 32 GB / Windows 11 (NT 10.0.26200) |
| JDK / GC | 21.0.12 / G1，`-Xms512m -Xmx1g -XX:MaxGCPauseMillis=200` |
| 数据库 | MySQL 9.0.1，同机，隔离级别 REPEATABLE-READ |
| 连接池 | HikariCP maximumPoolSize=10（压测实测 10.0） |
| 数据量 | paper_version 5000 行，其中 PUBLISHED 500 行 |
| 负载模型 | 开环（固定到达率），warmup 10s + 测量 30s |
| 限流 | 压测时放宽到 1,000,000/min，否则测的是限流器 |
