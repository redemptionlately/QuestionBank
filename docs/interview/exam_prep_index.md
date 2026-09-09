# 闭卷考试备考索引（逐题证据地图）

> 用法：考前往回跑对应证据 / 重读对应代码，把「是什么 → 为什么 → 项目里怎样」三层的
> 讲述顺序练熟。**这里只有指针没有答案**——闭卷考的正是你能否不看任何东西复述。
> 考完按 `closed_book_rubric.md` 批改（作答前别打开）。

| 题号 | 主题 | 动手复跑 / 重读 | 追问锚点（三层各一个） |
|---|---|---|---|
| Q1 Java | 泛型 PECS | `study/day50*`；项目里 `PaperResponse` record 与缓存接口的泛型用法 | 上界通配符集合能读不能写的**编译器理由**；为什么生产者用 extends |
| Q2 Spring | @Transactional 自调用 | `./scripts/mvn.sh test -Dtest=TransactionFailureModeTest`（5 条 [evidence]） | 注入引用是代理、this 是目标对象——两种身份；修复方式各自代价（自注入 / 拆类 / AspectJ） |
| Q3 MySQL | 隔离级别与行锁 | `MYSQL_EVIDENCE=true ./scripts/mysql-evidence.sh`；死锁 `output/mysql_deadlock_evidence.log` | RR 快照读 vs RC 当前读的分界；FOR UPDATE 锁与释放时机；errorCode 1205/1213 分别是什么 |
| Q4 Redis/MQ | cache-aside 顺序 + Outbox | `RedisCacheIntegrationTest`（afterCommit 淘汰）；`output/kafka_evidence_*.log` | 先删缓存再更库的旧值回填时序；Outbox 解决"改库成功但发消息失败"；at-least-once + 幂等 = 不丢不重 |
| Q5 安全 | JWT 校验顺序 + IDOR | `auth/ApiTokenFilter`（校验链）+ BankService 资源归属校验位置 | 签名/过期/角色/归属各拦在哪一层；IDOR 为什么在 service 层修而不是 controller |
| Q6 Linux/部署 | 接口慢分层排查 | `scripts/prometheus-evidence.sh` 的指标维度；分层命令 DNS→TCP→TLS→服务端→DB | 每层"一条命令+看什么数字"；本项目对应证据（Hikari 池 / http_server_requests 直方图） |
| Q7 JVM/性能 | CPU 飙高证据链 | `output/jfr_analysis_*.log`（BCrypt 84% 就是完整案例）；loadtest + JFR | top -H → 线程 → 栈的链条；JFR 视图与 jstat 的取舍；怎么区分业务热点与 GC 线程 |
| Q8 系统设计 | 深分页 | `output/mysql_evidence_*.log` 的 EXPLAIN 对照（LIMIT 越深扫描越多） | OFFSET 为什么全扫；keyset 的 WHERE 条件怎么写；tiebreaker 防什么（排序不稳定） |
| Q9 微服务 | 重试放大 | 网关重试 × 服务重试的乘法；本项目 Outbox 的重试上限设计（maxAttempts 后停 FAILED 等人工） | 3×3=9 倍怎么算；退避/预算/熔断三种手段的边界 |
| Q10 分布式 | 锁的失效 | `RedisGuardedSubmitIntegrationTest`（故意绕锁仍正确）；RedisLockService fencing token | GC 停顿锁过期时序；唯一键兜底与 fencing 单调递增各防什么 |
| Q11 ES | text/keyword 与深分页 | 未接入（如实说），对照 MySQL 侧 LIMIT 证据讲原理 | 分词与聚合字段选择；from+size 的 window 限制；search_after 的排序键要求 |
| Q12 Java | AQS 与锁语义 | `./scripts/mvn.sh test -Dtest=AqsEvidenceTest -Dsurefire.failIfNoSpecifiedTests=false`（6 条 [evidence]） | state 的两种语义（独占=重入计数 / 共享=剩余许可）；tryAcquire 失败后如何入队与 park；**tryLock 对持有者自己是重入成功、对其他线程才立即返回 false**（最易答错）；await 为什么必须先释放锁 |
| Q13 JVM | 类加载与双亲委派 | `./scripts/mvn.sh test -Dtest=ClassLoaderEvidenceTest -Dsurefire.failIfNoSpecifiedTests=false`（4 条 [evidence]） | 类身份由"类本身 + 定义它的加载器"共同决定（同名不同加载器互转 CCE）；委派是 loadClass 的默认实现、重写即可绕过（约定非强制）；TCCL 为什么能"向下"看到子加载器的类（SPI / JDBC 驱动加载） |

## 三层追问自测法

每题问自己三遍，三遍都能不卡壳：
1. **是什么**：一句话定义 / 结论。
2. **为什么**：底层机制（编译器 / InnoDB / JMM / Kafka broker 行为）。
3. **项目里怎样 / 反例**：能指到 `output/` 的证据或说出失效时序。

凡第三层卡壳的，回去复跑那张证据表里的命令，亲手看一遍输出再重考。
