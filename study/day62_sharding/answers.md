# Current（复述答案要点，写你自己的话；对答案标准见 solutions.md）

- 拓扑复述：2 库 × 2 表、两逻辑表各 4 物理表；路由公式两条（库、表）都是 `student_id % 2`；为什么库与表必须同余一致（错位组合为空的叉积断言）。
- 三处 schema 改造复述：主键无 AUTO_INCREMENT（SNOWFLAKE 补）、FK 移除（跨库不可达）、submission_item 冗余 student_id（免 join 路由）；每条的代价能各说一句。
- Actual SQL 三形态复述：带分片键 1 条下推；无条件 4 条广播归并；binding join 对齐 1 条（否则 2×2 笛卡尔积）。
- 唯一键边界复述：物理 uk 只拦同分片；跨分片同业务键不拦（实测插入成功）；全局唯一要么唯一键含分片键、要么全局索引。
- 自动 vs 手动算法复述：MOD/HASH_MOD 只配 autoTables；手写 actualDataNodes 用 INLINE；混用的报错叫什么。

# External

- 扩容三路径复述：停机迁移 / 双写+灰度 / 一致性哈希，各自的迁移面与回滚点；MOD 求模为什么扩容是全量重路由。
- 排行榜复述：广播归并 vs 异构聚合（ZSet/ES）的取舍，新鲜度-成本-复杂度三角。
- 跨片事务复述：单分片本地事务 + 跨片 Outbox 最终一致；XA 为什么慎用。
- SS 接入四坑复述：YAML 平铺格式（props: 嵌套被静默忽略）、pool-hikari 不在 Maven 依赖图、自动算法不配 actualDataNodes、surefire 3.5.3 参数改名。
