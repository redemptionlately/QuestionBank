# MustRemember

- 分库分表的分片键决定一切：路由正确性、热点分布、跨分片查询成本、唯一键有效性、扩容迁移代价。选键先画查询画像（哪些等值条件最高频），再谈算法。
- 手写 `actualDataNodes` 的表用**手动分片算法**（INLINE 等）；`MOD/HASH_MOD` 等属**自动分片算法**，只允许出现在 `autoTables`——混用直接 `AlgorithmInitializationException`（本项目实测）。
- 自增主键（IDENTITY）在分片下只保证**单物理表内唯一**，全局唯一必须换分布式 ID（SNOWFLAKE 由 SS `keyGenerateStrategy` 自动填充），物理表主键因此不能再带 AUTO_INCREMENT。
- 跨分片外键（FK）不可达：分片表之间、分片表对全局表都无法建 FK，引用完整性由**应用层 + 唯一键**保证——这是分片的既定代价，不是疏忽。
- 物理唯一键只拦"落在同一物理表"的重复：业务唯一性想要全局有效，唯一键必须能推导出同一分片（把分片键纳入唯一键），否则要上全局索引/中心化服务。
- 广播表（BROADCAST）：小表全量冗余到每个库，一次逻辑写入下发全部物理副本，join 时本地化。适合低频写的字典/配置表。
- bindingTables：父子表用相同分片键 + 对齐算法时声明绑定，join 路由按下标对齐下推，消除 N×M 笛卡尔积（本项目实测 4 条 → 1 条 Actual SQL）。
- 子表冗余父表的分片键（如 submission_item 冗余 student_id）：让子表**不 join 父表也能路由**，是分片schema 设计的标准动作；代价是写入要维护冗余列一致性。
- SS 5.5 起数据源 YAML 为**平铺格式**：`jdbcUrl/username/password` 直接写在数据源条目下；旧文档的 `props:` 嵌套写法在 5.5.2 被静默忽略（javap 反编译 swapper + 官方文档双确认）。
- `shardingsphere-jdbc` 的 Maven 依赖图**不含** `shardingsphere-infra-data-source-pool-hikari`（发行包才带）：缺它则 PoolMetaData SPI 加载不到 → 同义词表为空 → 标准 props 全部静默丢弃，症状是 `Access denied for 'OS用户'@'localhost'` 或 `StorageUnit` NPE。
- 外部源码索引（MustRemember）：[ShardingSphere 5.5.2 YAML data source](https://shardingsphere.apache.org/document/5.5.2/en/user-manual/shardingsphere-jdbc/yaml-config/data-source/)、[SNOWFLAKE paper](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake)

# MustUnderstand

- sql-show 的 Actual SQL 就是路由层的铁证：带分片键 = 单片下推（1 条 Actual SQL）；无分片条件 = 全路由广播再归并。看 Actual SQL 数量与目标表名即可裁定路由行为，不需要猜。
- 归并（merge）是 SS 的第二半边：跨片 `GROUP BY/COUNT/ORDER BY/LIMIT` 都要内存/流式归并，分页 OFFSET 越深归并代价越大——所以分页设计要带分片键或游标。
- 单库分表先行、按容量拆库的节奏是对的：M0 单库更容易验证不变量；本项目直接在证据环境里演示 2 库×2 表，是为了让"库与表同余错位为空"这类叉积断言变成可复现事实。
- 扩容（2 库 → 4 库）在 MOD/INLINE 求模方案下是**全量重路由**：要么停机迁移，要么双写+灰度切换。一致性哈希/范围分片可减少迁移面，但各有热点与倾斜代价——面试要能说出"先量化数据量与增速，再决定是否拆、怎么拆"。
- 分片与读写分离正交：主从解决读扩展，分片解决写/容量扩展；两者叠加时每片内部仍是主从结构。
- 外部源码索引（MustUnderstand）：[ShardingSphere sharding algorithms](https://shardingsphere.apache.org/document/5.5.2/en/user-manual/common-config/builtin-algorithm/sharding/)
