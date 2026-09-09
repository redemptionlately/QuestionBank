# Day62 分库分表 · 题目与标准解答（Solutions）

> 本主题已在 `app/` 落地并可复跑（`MYSQL_SHARDING_EVIDENCE=true bash scripts/sharding-evidence.sh`，6 项证据）。
> 每题先给标准解答，再给"本项目实锤"——面试时两者都要能说。

## Current

### Q1. 分库分表解决什么问题？为什么不早拆、又为什么现在就做证据？

**标准解答**：解决**单机写容量与单表规模**（行数过大：B+ 树层高上涨、DDL 时长爆炸、备份窗口失控）。它不解决"代码写得烂"。拆分时机 = 单表行数/增速、磁盘与内存水位、DDL/备份时长任一逼近阈值，且已用读写分离、归档、冷热分层等更便宜的手段仍不够。过早拆牺牲 join/事务/唯一键的简单性。

**本项目实锤**：M0 主线仍走 JPA 单库（业务正确性优先）；分片以**独立 SS DataSource + 纯 JDBC 证据测试**落地（`ShardingEvidenceTest`，test scope，不占 Spring 上下文）——能力前置验证与生产引入是两回事，简历上可说的正是"边界感 + 可复现证据"。

### Q2. 分片键怎么选？写出行级路由公式与拓扑。

**标准解答**：先画查询画像——最高频等值条件做分片键（本场景是 student_id：学生会话/答题永远按学生查）；避免单调递增键造成写入热点；算法求模简单均匀但扩容全量重路由，范围/一致性哈希可减少迁移面但各有倾斜代价。

**本项目实锤**：
- 拓扑：2 库（question_bank_sharding_0/1）× 2 表（_0/_1），`actualDataNodes: ds_${0..1}.practice_session_${0..1}`；
- 路由：INLINE `ds_${student_id % 2}` + `practice_session_${student_id % 2}`（库与表同余一致）；
- 实测分布（[evidence-1]）：student 1..8 → `ds_0.session_0={2,4,6,8}` / `ds_1.session_1={1,3,5,7}`，错位组合 `_0._1`/`_1._0` 均 **0 行**——叉积断言证明库表路由不漂移；
- 手动算法选 INLINE 的原因（实测坑）：`MOD/HASH_MOD` 是自动分片算法，只许配 `autoTables`，手写 actualDataNodes 直接 `AlgorithmInitializationException`。

### Q3. 分片后主键、外键、唯一键分别怎么处理？

**标准解答**：主键换分布式 ID（SNOWFLAKE，趋势递增、时间戳高位）；FK 移除（跨库不可达，完整性由应用层保证）；唯一键要么"能推导同一分片"（把分片键纳入唯一键），要么上全局唯一索引——物理 uk 只在单表内生效。

**本项目实锤**：
- 8 张物理表主键全部无 AUTO_INCREMENT，SS `keyGenerateStrategy: SNOWFLAKE` 自动填充，8 个 ID（≈1.3×10^18）全局唯一不冲突（[evidence-4]）；
- 物理表 FK 全部移除；
- 唯一键边界如实固化（[evidence-6]）：同分片内重复提交 (session, q101) → `Duplicate entry` 被拒；跨分片同业务键（student2 引 student1 的 session+q101，路由到另一库）物理**不拦**——分片后业务唯一性必须把分片键纳入唯一键，这是 sharding 的经典代价，测试把它钉死而不是藏起来。

### Q4. sql-show 的 Actual SQL 怎么读？单片下推、广播归并、绑定表 join 各是什么形态？

**标准解答**：Logic SQL 是应用写的，Actual SQL 是路由后真正下发每个物理表的。带分片键等值条件 → 单片下推（1 条 Actual SQL）；无路由条件 → 全路由广播 + 结果归并（N 条 Actual SQL，GROUP BY/COUNT/分页都要归并）；绑定表 join → 按下标对齐下推（1 条），不声明 binding 就是 N×M 笛卡尔积。

**本项目实锤**（全部来自 `output/sharding_evidence_*.log`）：
- [evidence-2] `WHERE student_id=1` → `Actual SQL: ds_1 ::: SELECT id, status FROM practice_session_1 WHERE student_id = ? ::: [1]`，**恰好 1 条**，不含 `_0` 表名；
- [evidence-3] 无条件 `GROUP BY student_id` → **4 条** Actual SQL 分发 4 物理表 → 归并 8 组、SUM=8；
- [evidence-5] bindingTables 声明后，`practice_session ps JOIN submission_item si ON si.session_id = ps.id WHERE ps.student_id=1` → **1 条**对齐 Actual SQL（`ds_1 ::: ... FROM practice_session_1 ps JOIN submission_item_1 si ...`），笛卡尔积 2×2=4 被消除。

### Q5. 广播表是什么？子表为什么冗余分片键？

**标准解答**：广播表=小表全量冗余到每个库（字典/配置类，低频写），写入下发全部副本、join 本地化。子表冗余父表分片键=让子表**不 join 父表也能路由**，否则"按学生查答题"要先路由父表再带着 session_id 二次路由，跨片 join 成本爆炸。

**本项目实锤**：
- `exam_dict` 声明 `!BROADCAST`：一次逻辑 INSERT → **2 条** Actual SQL 下发 2 库，直查两库各 1 份副本（[evidence-4]）；
- `submission_item` 冗余 `student_id` 作分片键（V1 原表没有），建表注释里写明"免 join 即可路由"。

### Q6. SS 5.5 接入本身踩了哪些坑？

**本项目实锤**（原始日志都在 output/）：
1. **数据源 YAML 必须平铺**：`jdbcUrl/username/password` 直接写在条目下；旧文档 `props:` 嵌套写法在 5.5.2 被静默忽略——javap 反编译 `YamlDataSourceConfigurationSwapper`（只读顶层键与 `customPoolProps`）+ 官方 5.5.2 文档双确认。症状：`Access denied for 'Allen'@'localhost'` / `StorageUnit` NPE；
2. **Maven 依赖图不含 pool-hikari**：`shardingsphere-infra-data-source-pool-hikari` 只在发行包里，必须显式加坐标；缺它则 HikariDataSourcePoolMetaData SPI 加载不到 → 同义词表空 → 标准 props 全丢；
3. **自动 vs 手动分片算法**：MOD 仅限 autoTables（见 Q2）；
4. 附带：surefire 3.5.3 把 `-DfailIfNoSpecifiedTests` 改名为 `-Dsurefire.failIfNoSpecifiedTests`，旧参数静默失效会让多模块 reactor 直接 BUILD FAILURE。

# External

### E1. 2 库扩 4 库怎么迁？

**要点**：MOD 路由下 `student_id % 2` → `% 4`，一半数据要搬家。三条路径：停机迁移（导出按新路由重放，窗口=导出+导入时长）；双写+灰度（写两份、读切灰度、对账收敛后切主）；一致性哈希（只迁 1/N，但引入虚拟节点倾斜管理）。切换判据：对账差异=0 持续 N 分钟 + 双写延迟低于阈值；回滚点：读路由一键切回旧拓扑。先量化：单表行数、增速、binlog 带宽决定"值不值得"。

### E2. 排行榜这类跨片聚合怎么做？

**要点**：实时广播归并（SS 帮你合并，但代价随分片数线性涨，深分页最惨）vs 异构聚合（Binlog → 数仓/ES/Redis ZSet，最终一致）。答题排行榜选 ZSet：写入侧双写或 Binlog 订阅，读侧 O(logN)，新鲜度秒级可接受。要能说出"新鲜度-成本-复杂度"三角。

### E3. 跨分片事务边界怎么划？

**要点**：能落单分片的强一致就留本地事务；跨分片用消息/对账最终一致，慎用 XA（锁窗口跨库放大）。本项目"发布试卷"若跨 2 库：题目主数据与练习快照分属两库，快照本来就该异步/消息同步——发布事务仍在题目库本地收口，practice 库消费事件落快照（Outbox 模式原样复用）。
