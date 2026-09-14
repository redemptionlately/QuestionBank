# Day59 Distributed Consistency · 题目与标准解答（Solutions）

> 分布式 ID、分布式锁、跨服务一致性、分片。

## Current

### Q1. 设计 64 位 Snowflake 位段，说明时钟回拨与节点冲突。
经典分配（共 64 bit）：
```
| 1 bit 符号(恒0) | 41 bit 毫秒时间戳 | 10 bit 工作节点 | 12 bit 毫秒内序列号 |
```
- 41 bit 毫秒：约 69.7 年寿命（相对自定义 epoch）；
- 10 bit 节点：最多 1024 个节点；12 bit 序列：单节点每毫秒 4096 个，溢出则等下一毫秒；
- **时钟回拨**：记录 lastTimestamp，若当前时间 < last：小幅回拨（如 <5ms）可等待追平；较大回拨直接拒绝发号并告警，或用备用位；绝不能在“过去时间”发号导致重复；
- **节点冲突**：两个实例拿到相同 workerId 会撞 ID，需由注册中心/配置中心分配并校验唯一，或用 MAC/容器实例号 + 租约；节点号位数决定可部署规模上限。
- **本项目证据**：ShardingSphere 分片配置（[test/resources/sharding-evidence.yaml](../../app/src/test/resources/sharding-evidence.yaml)）主键生成器配 **SNOWFLAKE**，ShardingEvidenceTest.evidence04_broadcastAndSnowflakeId 断言 8 个雪花 ID 全局唯一且量级 >10^17（同时验证广播表 exam_dict 一写两库各一份）；该测试仅 test scope、门控 MYSQL_SHARDING_EVIDENCE，默认构建跳过。

---

### Q2. 带 token 校验的 Redis 锁获取/释放伪代码，说明 TTL/续租/fencing 风险。
```
# 获取：SET 带 NX（不存在才成功）与 EX（TTL），value=唯一 owner token（UUID）
SET lock:job:123 <ownerToken> NX EX 10
# 释放：compare-and-delete（Lua 保证“判断是自己的锁 + 删除”原子）
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else return 0 end
```
- **TTL**：防止持锁进程崩溃后死锁；时间要略大于临界区正常耗时；
- **续租（watchdog）**：持锁线程在未完成时定期延长 TTL，完成即停止；**诚实边界：本项目没有实现 watchdog 续租**，RedisLockService 用固定 TTL（提交锁场景为 10s），临界区设计为远短于 TTL；
- **fencing 风险**：若进程发生 STW/网络分区，锁可能在它不知情时过期并被别人拿到——此时两个客户端都以为自己持锁。对需要强保护的资源（如写 DB），用**单调递增 fencing token**：每次获锁拿到递增号，下游（DB/存储）拒绝比已见号更小的写，Redis 锁本身无法阻止“过期后双写”。锁只保护临界区，**不替代数据库唯一键与幂等**。
- **本项目真实实现（redis/RedisLockService，app.lock.backend=redis 时装配）**：tryLock(name,ttl) 用 `setIfAbsent(key, uuidToken, ttl)`（SET NX PX 语义），抢锁成功后对独立 key `qb:lock:fencing` 做 INCR 取单调递增 fencing token，返回 AutoCloseable 的 LockHandle；RELEASE_LUA 先 GET 比对 token 相等才 DEL，防止误删他人的锁。使用方 practice/RedisGuardedPracticeSubmitter 按 `practice-submit:{sessionId}` 加锁、TTL 10s，**锁在事务外获取、finally 里 unlock（事务提交后才释放）**，抢锁失败抛 409 SUBMIT_IN_PROGRESS；正确性最终仍由 DB 的 SELECT…FOR UPDATE 行锁与 uk_submission_question 唯一键兜底。证据：RedisLockAndRateLimitIntegrationTest 三个锁用例（互斥且仅持有者可释放、TTL 自动过期、fencing 单调递增）+ RedisGuardedSubmitIntegrationTest 两用例（锁串行化、绕过 Redis 锁时 DB 行锁仍保正确），门控 REDIS_EVIDENCE。

---

### Q3. “创建导入任务→生成候选→发布”选 Outbox/Saga/TCC，列失败状态与补偿。
这是跨步骤、可长时间运行、每步可补偿的流程，选 **编排式 Saga**（配合 Outbox 发事件）：
| 步骤 | 成功状态 | 失败/补偿动作 |
|---|---|---|
| 创建导入任务 | RECEIVED | 参数非法直接拒绝，无副作用 |
| 解析生成候选 | PROCESSING、候选暂存为草稿 | 失败→FAILED，删除/标记候选草稿不可用（补偿） |
| 人工/校验确认 | 候选 READY | 校验不过→退回 PROCESSING 修正 |
| 发布 | PUBLISHED（本地事务） | 发布失败→回退到 READY/DRAFT（补偿，不能删已发布历史则置撤销态） |
- **空回滚**：补偿先于原请求到达（如原请求超时）时，补偿要识别“没执行过”，幂等空操作；
- **悬挂**：补偿之后迟到的原请求不得再执行（记录状态拦截）；
- **补偿也可能失败**：需重试 + 人工介入队列 + 审计，Saga 补偿不是数据库回滚，业务允许中间态。
TCC（Try 预留/Confirm/Cancel）适合强一致、资源可预留（如账户余额），本场景候选生成无需预留资源，TCC 过重；Outbox 负责“状态变更可靠发事件”，与 Saga 配合使用。本项目已落地的两块真实拼图：① import_job 状态机 RECEIVED/PROCESSING/SUCCEEDED/FAILED + ImportJobRetrySweeper（FAILED 且 attempt<3 时按 30/60/120s（封顶 600s）退避重排回 RECEIVED 并异步重跑，@Version 乐观锁防并发覆盖）；② event 包 Outbox（同事务写 outbox_event、Relay 投递 qb-events、consumed_event 幂等、.DLT 兜底毒丸）。注意本项目没有实现 Saga 编排器/TCC 框架，跨步骤长流程补偿仍是设计层面。

---

## External

### E1. 设计分片键，分析热点、跨分片分页、扩容迁移。
- **分片键选择**：优先高基数、查询高频过滤列、与主要事务同键（如 student_id），让单学生数据落同分片；
- **热点**：按单调自增/单一热门键会写集中到一个分片，可用 hash 打散或对热点单独处理；
- **跨分片查询/分页**：无法用单分片 order by+limit，需各分片取 topN 归并（代价高），或用异构索引/搜索投影；全局唯一 ID 用 Snowflake；
- **扩容迁移**：一致性 hash/虚拟节点减少迁移量，双写+校验+灰度切流，分片数与路由规则要可配置；分库分表是后期容量方案，过早拆牺牲事务与查询简单性（app 主线保持 JPA 单库）。
- **本项目证据（仅 test scope，不进生产 classpath）**：[sharding-evidence.yaml](../../app/src/test/resources/sharding-evidence.yaml) 配 2 库 ds_0/ds_1，practice_session/submission_item 各 4 物理表（actualDataNodes=ds_${0..1}.xxx_${0..1}），库/表分片键均 student_id、INLINE 算法 `ds_${student_id % 2}` / `表_${student_id % 2}`，bindingTables 消除 join 笛卡尔积，exam_dict 为广播表；ShardingEvidenceTest 6 个有序用例分别固化路由落位与错位叉积 0 行、单分片只下发 1 条 Actual SQL、无分片键 GROUP BY 广播 4 表归并、广播表+雪花 ID、绑定表 join 对齐 1 条 SQL、分片后唯一键必须含分片键的边界。运行方式：[scripts/sharding-evidence.sh](../../scripts/sharding-evidence.sh)（导出 MYSQL_SHARDING_EVIDENCE=true 后只跑 ShardingEvidenceTest，日志 tee 到 output/），依赖 shardingsphere-jdbc 5.5.2（test scope）。

### E2. 比较数据库唯一键、Redis 锁、fencing token 在重复提交中的作用。
- **数据库唯一键**：最终防线，保证同业务键物理上只存一行（约束在数据层，最可靠）；
- **Redis 锁**：入口互斥、降低并发冲突概率，但会过期/受主从切换影响，不能作为唯一正确性保证；
- **fencing token**：单调递增令牌，让下游存储拒绝过期写者，解决“锁失效后双写”；
正确组合：Redis 锁挡大部分并发 + 数据库唯一键兜底 + 幂等键返回一致结果，三者层次不同、互补而非替代。本项目四层齐备且各有测试：① Redis 锁 RedisLockService（减冲突、带 fencing token INCR）；② SELECT…FOR UPDATE 行锁（PracticeService.submit，同会话串行）；③ uk_submission_question(session_id,question_version_id) 与 submission_key 列做最终物理兜底；④ Idempotency-Key 头保证同 key 重放同一结果（M0FlowIntegrationTest.concurrentSubmitWithSameKeyReturnsOneStableResult），RedisGuardedSubmitIntegrationTest.databaseRowLockStillGuardsCorrectnessWhenRedisLockIsBypassed 专门证明绕过 Redis 锁后 DB 层仍保正确。

### E3. 网络分区时一致性、可用性与用户体验取舍（CAP/BASE）。
CAP 只在发生网络分区（P 必然存在）时在 C 与 A 间取舍：选 CP（如 etcd/ZooKeeper）分区时少数侧拒绝服务以保证数据不错乱；选 AP（如多数缓存/DNS）分区时各侧继续响应、事后合并。业务上用 BASE：允许短暂不一致但必须给出**收敛条件与用户可见状态**（如“处理中，稍后刷新”），并通过补偿/对账最终一致。对题库“提交判分”这类核心事实优先强一致（单库事务），对“已发布列表展示”可接受短暂延迟（缓存/ES 最终一致）。
