# Current（对应 app/ 分片证据实现，全部可在本机复跑）

- 复跑 `MYSQL_SHARDING_EVIDENCE=true bash scripts/sharding-evidence.sh`，逐项核对 6 条 `[evidence]` 输出；对每一项能回答"它证明了什么、失败会长什么样"。
- 背写拓扑：2 库（question_bank_sharding_0/1）× 2 表（_0/_1），practice_session + submission_item 各 4 物理表；student_id % 2 决定库与表，INLINE 表达式即路由公式。
- 打开 `output/sharding_evidence_*.log` 找 `Actual SQL:` 行：解释 evidence-2 为什么只有 1 条（单片下推）、evidence-3 为什么是 4 条（全路由广播）、evidence-5 为什么又回到 1 条（binding 对齐）。
- 直查物理库验证分布：`SELECT student_id FROM question_bank_sharding_0.practice_session_0`，对照 `[evidence-1]` 的 {2,4,6,8}；再查 `_0.practice_session_1` 应为空——说出这组"叉积为空"断言证明的是什么（库与表同余一致，无路由漂移）。
- 用 `SHOW CREATE TABLE question_bank_sharding_0.practice_session_0` 指出三处分片化改造：主键无 AUTO_INCREMENT、submission_item 冗余 student_id、FK 全部移除；每一条都要能说出代价。
- 唯一键边界实验：手工向 `submission_item_1` 重复插入同一 (session_id, question_version_id) 看 Duplicate entry；再向 `submission_item_0` 插入相同业务键（student_id 改偶数）看它成功——解释为什么"同业务键跨分片不拦"是物理层必然。
- 改坏一次验证一次：把 tbl-item-inline 的表达式改成 `submission_item_${student_id % 4}`，重启证据跑批，观察 binding join 断言失败与 `[evidence-1]` 分布断言失败——解释路由不对齐时 bindingTables 退化成什么。

# External

- 设计 2 库 → 4 库扩容方案：列出停机迁移 / 双写+灰度 / 一致性哈希三种路径各自的迁移面、切换判据与回滚点；结合本项目 MOD 路由说明为什么求模方案扩容是全量重路由。
- 设计"按 student_id 查答题记录 + 全站答题排行榜"两个查询在分片下的形态：前者单片下推，后者要么广播归并、要么走异构聚合（Binlog → 数仓/ES）；给出两个方案的数据新鲜度与一致性权衡。
- 讨论分布式事务在分片下的边界：单分片内本地事务 + 跨分片最终一致（消息/对账），与 Seata AT 的取舍；说出本项目"发布试卷跨 2 库"时事务边界应划在哪。
- 设计唯一键的三个层级：物理 uk（分片内）→ 业务唯一键含分片键 → 全局唯一索引服务；各自拦截什么、漏什么、成本是什么。
- 面试追问："数据量多大才需要分库分表？"——先给量化框架（单表行数、磁盘/内存水位、备份窗口、DDL 时长），再说本项目为什么在 M0 就把分片证据做出来（能力前置验证，不是容量需要）。
