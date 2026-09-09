# 外部审计核实与修复报告（2026-09-08）

> 审计声称：全仓库逐字审核、实际评分 91/100，列出 4 个严重安全漏洞 + 10 个高优先级 + 20 个中优先级问题。
>
> 本报告不采信审计文本本身——每条指控都重新读代码取证，结论分三类：
> **属实已修复** / **审计误读（不改码，附反证）** / **设计选择（补注释说明权衡）**。

## 一、严重漏洞（4 项指控 → 3 真 1 误报，全部闭环）

| # | 审计指控 | 核实结论 | 处置 |
|---|---------|---------|------|
| 1 | app 题目详情接口泄露 `answerJson` | **误报**。`BankController.QuestionResponse`（app/src/main/java/com/allen/questionbank/bank/BankController.java:64）字段为 `id, questionNo, prompt, type, optionsJson, score, explanation`——**没有 answerJson**。`optionsJson` 是选项非答案；`explanation` 是解析，复习场景属有意下发 | 不改码，记录澄清 |
| 2 | cloud `addQuestion` 无角色校验，学生可给任意题库加题 | **属实**。原实现连 `X-User-Role` 头都不读取 | 已修复（见下） |
| 3 | cloud `publish` 无角色/所有权校验，任何人可发布任意题库 | **属实**。同上 | 已修复（见下） |
| 4 | 公开 `GET /api/banks/papers/{paperId}/snapshot` 返回含标准答案的快照，学生可整卷泄题 | **属实**。原实现无任何角色判断；快照 `Item.answer` 必须存在（判分在 practice 侧完成，见 PaperSnapshot 注释），错在公开通道没有闸门 | 已修复（见下） |

### 修复内容（cloud/bank-service/BankController.java）

- `requireTeacher(role)`：addQuestion / publish / snapshot 三处 + 原 createBank 的内联判断统一收敛到这一个入口。**为什么服务端必须再判一次**：网关是边界，内网直连可绕过网关，`X-User-Role` 透传头本身可伪造——服务自己是最后一道防线。
- `requireOwnership(role, userId, bankId)`：addQuestion / publish 加所有权校验（非 ADMIN 时 `bank.ownerId == userId` 才放行），新增 `BankService.getBank()` 支撑；题库不存在按 404 抛出，不向无权者泄露存在性。
- `snapshot` 收敛为 TEACHER/ADMIN-only：学生侧取题路径是 practice 建会话走 `/internal` 快照（Feign 服务间调用），公网不再存在"学生拿答案"的通道。

### 修复的回归证据（scripts/cloud-evidence.sh 新增第 11 项：越权防护）

| 锚点 | 期望 |
|------|------|
| 学生 token 给教师题库加题（经网关） | 403 |
| 学生 token 发布教师题库（经网关） | 403 |
| 学生 token 拉答案快照（经网关） | 403 |
| 直连 bank + 伪造 `X-User-Role: TEACHER` + 伪造 userId 跨主发布 | 403（所有权兜底，证明"最后防线"成立） |

锚点 4 的设计意图：模拟"网关被绕过/配置错误"的最坏情况——即使攻击者伪造了角色头，服务端的所有权校验仍独立拒绝。四个锚点全部硬断言 403，任一不符整个证据流程 `return 1`。

## 二、高优先级问题（属实部分全部修复）

1. **RedisGuardedPracticeSubmitter 未接线**（条件开关形同虚设）——属实。`@ConditionalOnProperty(app.lock.backend=redis)` 组件存在但 Controller 从未走它，开关开了也没用。
   修复：`PracticeController` 经 `ObjectProvider<RedisGuardedPracticeSubmitter>` 注入，submit 先取 `CurrentUser.require()`，guard 存在走锁协调路径（锁在事务外获取、提交后释放），否则回退直连 `PracticeService.submit`。开关关闭时行为与原来逐字节一致。
2. **DevDataInitializer 无条件播种弱密码**（admin/admin123）——属实。
   修复：`@ConditionalOnProperty(app.dev-data.enabled, havingValue="true", matchIfMissing=true)`。默认开启保证全部既有证据链零变化；生产部署显式 `app.dev-data.enabled=false` 关闭。
3. **GlobalExceptionHandler.handleUnexpected 吞异常**（500 只回 requestId，服务端却没记日志）——属实。
   修复：app GlobalExceptionHandler、cloud bank ErrorHandling、cloud practice ErrorHandling 三处补 `log.error`（方法 + URI + requestId + 堆栈）。业务异常仍不打日志（不吵）。
4. **RateLimitFilter 的 clients map 无清理**（key 含 URI，可用随机 URI 无限撑内存）——属实。
   修复：每 256 个请求惰性 `removeIf` 清理过期窗口。判据与 compute 的过期判据完全一致（`isBefore`）；清理放在 compute 外（CHM.compute 契约禁止 lambda 内再动其他映射）。
5. **OutboxPublisher existsBy+save 竞态**——属实。两个并发事务同时通过 existsBy 后同时 insert，后到者撞 `uk_outbox_dedup`，异常在 commit 时才爆（JPA 延迟 flush），业务事务被连坐回滚。
   修复：写入改 `JdbcTemplate` 原生 `INSERT ... ON DUPLICATE KEY UPDATE id = id`——单语句原子，撞键 = 0 行受影响、静默跳过、业务事务继续提交。**为什么不用 JPA saveAndFlush + catch**：flush 失败后 Hibernate 会话按契约已不可信，等于把业务事务搭进去。**为什么不用 INSERT IGNORE**：它把"数据超长"等其他错误也吞成 warning。JdbcTemplate 走当前事务的连接，事件与业务写入仍同事务。existsBy 保留为廉价快路径（省序列化）。
   测试：OutboxTest 四个用例同步改造（写入验证改为 jdbc.update 参数级断言）；H2 MODE=MySQL 对 ON DUPLICATE KEY 的兼容性由全量 100 测试实测背书。
6. **gateway yml zone-preference 注释自相矛盾**——属实（注释说"关掉 zone 亲和"，配置却在启用 zone-preference）。无 zone 元数据时它本身是 no-op。
   修复：删除该死配置块（零行为变化），由 cloud-evidence 全流程重跑背书路由不受影响。
7. **PaperSnapshot.Item.type 注释词表错误**（SINGLE/MULTI/JUDGE vs 实际 SINGLE/MULTIPLE/TRUE_FALSE）——属实。
   修复：注释统一为真实词表 + bank-service `addDraft` 加题型白名单（Set.of("SINGLE","MULTIPLE","TRUE_FALSE")），拼错直接 400，注释与代码互相锁死。
8. **cloud createBank 未校验 name**——属实。
   修复：`BankService.createBank` 空名/blank 直接 400。
9. **TokenService 用 `|` 分隔 payload，username 含 `|` 时 token 永远验不过**——属实（当前无注册接口，播种用户名是常量，故非现实漏洞，但约束没有任何一处声明）。
   修复：`issue()` 签发点兜底快速失败（`IllegalArgumentException`），注释声明"任何注册/播种入口必须拒绝含 | 的 username"的不变量。
10. **AuthFilter 每请求实时验签缺权衡说明**——补注释作为第 4 个设计决定：缓存验签结果省一次往返，但角色直接决定授权边界，缓存把"改角色立即生效"变成"等缓存过期"，是**反向安全**；验签无副作用，实时成本可接受。

## 三、审计误读澄清（不改码，附反证）

| # | 审计指控 | 反证 |
|---|---------|------|
| 1 | OutboxRelay"一批 50 个事件失败会回滚已成功的 49 个" | 异常在**循环内逐事件 catch**（OutboxRelay.java:57 `catch (Exception sendFailure)`）：一个事件投递失败只标记自己 FAILED，循环继续处理下一个，不存在"连坐回滚"的路径 |
| 2 | OutboxRelay"失败直接 return，丢事件" | `return` 只出现在 `catch (InterruptedException)`（:54-56）——线程被中断时退出投递循环是正确语义，不是吞错误；普通失败走 :57 的 catch 继续 |
| 3 | requeue 不重置 retryCount，重试上限会误杀 | **单调递增是设计**：重试上限判定的就是"这个事件累计失败了多少次"，重置反而让坏事件无限重试 |
| 4 | app 与 cloud 的 Role 枚举不一致（app 无 TEACHER，cloud 无缺位） | 两套独立系统、零跨模块调用：app（单体）业务上只有 ADMIN/STUDENT；cloud（微服务）有教师出题角色。各自的词表在自己的边界内自洽，"不一致"不成立 |
| 5 | V3 迁移 `UNIQUE (owner_id, id)` 冗余 | 属实（id 自增主键已唯一），但 Flyway checksum 已锁定，改迁移 = 全环境手工修复；行为无害（多一个走不上索引的约束不改变任何读写语义），保持原样是正确权衡 |

### 本轮修复的运行时证据（scripts/phase3-audit-evidence.sh，可反复重跑）

```
[evidence] 消费者并发度（KafkaConsumerConfig.setConcurrency）= 3
[evidence] qb-events 分区数 = 3（要求 >= 3）
[evidence]   	Topic: qb-events	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
[evidence]   	Topic: qb-events	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
[evidence]   	Topic: qb-events	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
[evidence] qb_auth 的 GRANT 作用范围 = ON qb_auth.*      （qb_bank / qb_practice 同构）
[evidence] cloud 配置中仍用 root 连库的服务数 = 0（要求 0）
[evidence] 用 qb_auth 专用账号直连成功：CURRENT_USER() = qb_auth@127.0.0.1
[evidence] 空 username/password -> HTTP 400
[evidence] 错误体 = {"code":"VALIDATION_ERROR","message":"password: password 不能为空",...}
[evidence] 合法登录 -> HTTP 200（证明 @Valid 没有误伤正常请求）
```

三条分别是 Item 3（并发度 ≤ 分区数前提成立）、Item 12（专用最小权限用户 + 无 root 残留）、
Item 18（请求体校验 + 统一错误契约）的运行时闭环。

## 四、验证证据（全部可复跑）

```bash
# app 全量门禁（四证据开关，第三阶段后 112 测试 + 覆盖率门禁）
REDIS_EVIDENCE=true MYSQL_EVIDENCE=true MYSQL_SHARDING_EVIDENCE=true \
KAFKA_EVIDENCE=true ./scripts/mvn.sh -B clean verify
# → Tests run: 112, Failures: 0, Errors: 0, Skipped: 3; BUILD SUCCESS（2m55s）
#   新增 12：ExpiringCacheSingleflightTest 4 + InfrastructureExecutorRoutingTest 3 + ImportJobRetrySweeperTest 5

# cloud 编译 + 五服务端到端证据（含新增 6b 越权防护锚点）
./scripts/mvn.sh -B -f cloud/pom.xml package -DskipTests   # EXIT=0
./scripts/cloud-evidence.sh run                             # 11 项证据全绿（含 4/4 越权 403）
```

原始日志：`output/cloud_evidence.log`、`output/cloud_*.log`、surefire 报告。

## 五、边界与遗留

- 内部接口（/internal/**）靠网络边界保护、无服务间 mTLS/内部 token——维持"未接入项"声明，见覆盖度文档。
- 集群部署时 Redis 锁的多实例语义、限流 Redis 后端——已是既有设计声明，本轮未触碰。
- 中优先级 20 项的逐项核实见第六节（2026-09-09 追加，取代本节原首条"未逐项列出"的说明）。

## 六、中优先级 20 项逐项核实（2026-09-09 追加）

> 20 项全部重新读码取证，处置分三类：**本轮修复 6 项**、**本轮澄清（不改码）6 项**、
> **前轮已闭环 8 项**（Task #20 顺手处理 7 项 + 核实轮澄清 1 项，见第一~三节）。

### A. 本轮修复（6 项）

| # | 指控（转述） | 核实 | 修复 |
|---|-------------|------|------|
| 1 | cache-aside miss 路径无防击穿，热点 key 过期瞬间并发打库 | **属实**。`RedisPublishedPaperCache.getOrLoad` miss 直接 `loader.get()`；本地 `ExpiringCache.getOrLoad` 同病 | 双侧补单飞：fast path 无锁命中 → miss 后加锁 + 双检（Redis 侧 per-key 锁对象表，当前单热点 key 容量 ≤1；本地侧 synchronized(this) 粗粒度）。javadoc 声明边界：锁是 JVM 内的，多实例残余并发 = 实例数上限，由 TTL 抖动 + DB 兜底。测试：8 线程 CyclicBarrier 并发 miss 断言 loader 恰执行 1 次 |
| 2 | `AsyncConfigurer.getAsyncExecutor()` 返回 importTaskExecutor，所有 @Async 共用 core=2 的导入池 | **属实**（InfrastructureConfig.java:27）。当前唯一 @Async 用户是 ImportJobWorker，但任何新增异步都会被导入高峰饿死 | 新增 `generalTaskExecutor`（core2/max8/queue200，`general-async-` 前缀）作 @Async 默认池；两池各加 CallerRunsPolicy（宁可降速不丢任务，javadoc 声明吞吐变大后应改 Abort+503）+ 关停在途任务等待。测试：默认池路由断言 + 实际线程名前缀断言 |
| 3 | Kafka 消费并发 1，徒有幂等却无吞吐 | **属实**（KafkaConsumerConfig 无 setConcurrency） | `factory.setConcurrency(3)` + 注释锁死两个前提：分区数 ≥ 并发度（start-evidence-env.sh 幂等建 qb-events 3 分区，含已存在但不足 3 的 alter 纠偏）；per-key 有序靠发送侧 eventKey 作 key（OutboxRelay.java:52），跨 key 重复由 eventKey 唯一键幂等兜底 |
| 4 | cloud 三服务用 root 连 MySQL（密码默认硬编码 783421） | **属实**（三个 application.yml 均为 `username: root`） | 每服务专用最小权限用户（qb_auth/qb_bank/qb_practice，各限本库）；start-evidence-env.sh 幂等供给（CREATE USER IF NOT EXISTS + ALTER 重置密码 + GRANT 本库 + 预建 schema）；yml 切换并**移除 createDatabaseIfNotExist**（应用账号不该有全局建库权限），密码统一 `${CLOUD_DB_PASSWORD:...}` 可注入覆盖 |
| 5 | ImportJob FAILED 后永久停留，attempt 列形同虚设 | **属实**。V3 已有 attempt 列、start() 已自增，缺的只是触发器 | 新增 `ImportJobRetrySweeper`：@Scheduled 扫 FAILED + attempt<3，指数退避 30s×2^(n-1)（updatedAt 为等待起点，@PreUpdate 刷新），翻转 RECEIVED 后跨 bean 调 worker.process（@Async 代理生效，不占调度线程；同 bean 内 this.process 会绕过代理——单出组件的原因之一）。attempt 耗尽保持 FAILED 终态人工介入，不无限重试毒丸。测试：Mockito 5 用例（退避窗口内外 / 指数曲线 / 空列表短路） |
| 6 | cloud auth login 请求体零校验，与 app 单体不对称 | **属实**（`LoginRequest` 无注解，pom 无 starter-validation） | auth pom 加 starter-validation + cloud-common；`LoginRequest` 加 `@NotBlank`，login 加 `@Valid`；新建 auth `ErrorHandling`（@RestControllerAdvice，复用 cloud-common ErrorBody 统一错误契约，VALIDATION_ERROR 码与 app GlobalExceptionHandler 对齐，ResponseStatusException 透传 + 500 落日志） |

### B. 本轮澄清（6 项，不改码附反证）

| # | 指控（转述） | 反证 |
|---|-------------|------|
| 7 | ExpiringCache 过期项堆积内存泄漏 | 单热点 key 使用（BankService.java:107 固定 PUBLISHED_KEY），map 容量 ≤1；get() 本就有惰性删除。修复 #1 时顺手补了加载期全表清扫 + `size()` 观察，双保险 |
| 8 | RedisLockService 无 watchdog 续期，锁过期任务失控 | 设计选择：正确性契约不依赖锁长期有效——规则 3（javadoc）DB 唯一键兜底 + RedisGuardedSubmitIntegrationTest 绕锁并发实证。锁过期语义 = 拿不到锁回退无锁提交路径，仍安全。watchdog 是复杂度换便利且进程假死时续期反而延长占锁。已补 javadoc 声明 |
| 9 | PracticeService.submit 首次答题 `answers.get()` NPE | **误报**：代码里根本没有 answers map——`submissions.findBySessionIdAndQuestionVersionId(...).orElseGet(() -> new SubmissionItem(sessionId, question.getId(), "[]"))`（PracticeService.java:59-89），未答题 = 空串 JSON 判 0 分，无 NPE 路径 |
| 10 | question_bank / user_account 缺 entity_version 乐观锁 | **指错对象**：两张表 insert-only（V1__m0_schema.sql:11-19；UserAccount 无任何更新路径），加列 = 死列。真正可 UPDATE 的 paper_version（publish 状态迁移）靠唯一键 + 状态机幂等兜底 |
| 11 | user_account 缺 updatedAt | 同上：insert-only，没有 UPDATE 就没有"最后更新时间"的语义，列加了也不会有人写 |
| 12 | readiness 探针 SELECT 1 太浅，测不出慢查询 | 探针职责是"能否接流量"（连接池 + 连通性），业务查询延迟归 metrics（Hikari micrometer 自动暴露）——把慢业务查询塞进 readiness，DB 一次抖动摘掉所有实例，放大故障。已补 javadoc 声明边界 |

### C. 前轮已闭环（8 项，索引）

#6 requeue 不重置 retryCount（单调递增是设计，见第三节#3）· #7 AuthFilter 实时验签（设计注释，见二.10）· #8 zone-preference 死配置（已删，见二.6）· #9 PaperSnapshot 词表（已修，见二.7）· #10 TokenService `|` 分隔（已修，见二.9）· #11 ErrorHandling 吞异常（已修，见二.3）· #13 V3 UNIQUE 冗余（属实但保持原样，见第三节#5）· #19 createBank 空名（已修，见二.8）
