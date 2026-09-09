# Day28 M0 Interview Review · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实核心类与测试证据。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankService` 29-67 行草稿/发布；`PracticeService` 52-85 行提交/幂等；`PracticeSessionRepository` 悲观锁；V1 49-86 行持久化边界；`M0FlowIntegrationTest` 153-219 行回滚/并发/索引证据。

---

### Q2. 回答数据流和技术取舍。
后端项目陈述按固定事实结构展开：**业务目标 → 核心实体 → 请求数据流 → 事务边界 → 并发控制 → 持久化结果 → 失败响应**。
- 业务目标：管理员建题库/发布不可变试卷，学生练习、确定性判分、错题归集；
- 核心实体：QuestionBank→PaperVersion→QuestionVersion（题面聚合），PracticeSession→SubmissionItem/WrongQuestion（作答聚合）；
- 数据流：Controller（协议/DTO）→ Service（授权+状态机+事务）→ Repository → MySQL；
- 取舍：JPA（聚合 CRUD 清晰）、标量外键 ID（避免懒加载/级联失控）、追加式版本（历史快照可审计）、服务端判分（客户端不可信）。

---

### Q3. 运行测试并复述证据。
9 个测试方法分别证明：幂等提交、角色拦截、三题型判分、坏请求 400、草稿整体回滚、同 key 并发唯一结果、索引存在、异步任务流转、缓存只加载一次。复述时区分“测试证明了什么”（当前输入/环境下断言通过）与“没证明什么”（真实规模性能、跨实例）。

---

### Q4. 针对一个方案写出观测事实、推断、未知、替代方案和残余限制。
以“提交用悲观行锁”为例：
- **观测事实**：同 key 并发测试只产生一份结果；`findByIdForUpdate` 生成 FOR UPDATE；
- **推断**：行锁串行化了同一会话的状态检查与写入（由 InnoDB 锁机制支撑）；
- **未知**：高并发混合不同会话时的锁等待分布、真实吞吐（未压测）；
- **替代方案**：乐观锁 @Version、唯一键 + 插入冲突重试、通用幂等表、Redis 分布式锁；
- **残余限制**：锁只护同一行，不解决 key TTL/处理中恢复/跨业务幂等。

---

### Q5. 按源码索引说明各组件负责的事实。
| 组件 | 负责的事实 |
|---|---|
| BankService | 题库创建、草稿版本号递增与题目校验、发布状态机与幂等、缓存失效 |
| PracticeService | 会话创建状态校验、答案 UPSERT、确定性判分、幂等提交、错题聚合 |
| PracticeSessionRepository | `findByIdForUpdate` 悲观写锁、学生维度派生查询 |
| V1/V2 SQL | 表/外键/联合唯一键（永久结构事实）、查询索引 |
| M0FlowIntegrationTest | 端到端链路、回滚、并发、索引等行为证据 |

---

### Q6. 口述同 key 提交从请求头到数据库重放的完整因果链。
`请求带 Idempotency-Key → Controller @RequestHeader 取出 → CurrentUser.require() 得到学生 → submit() 校验 key（缺失/超长 400）→ findByIdForUpdate 对 session 行加锁 → 校验 studentId → 读状态：
- SUBMITTED 且 key 相同 → 直接反序列化 submission_result_json 返回（不判分、不写错题）；
- SUBMITTED 且 key 不同 → 409；
- IN_PROGRESS → 遍历 QuestionVersion 判分、写 SubmissionItem/WrongQuestion、session.submit 落总分与结果 JSON → 事务提交释放行锁 → 返回 SubmitResult。`

---

### Q7. 比较悲观锁、乐观锁、唯一键在 M0 三个并发冲突中的适用位置。

| 机制 | 原理 | M0 适用冲突 | 代价 |
|---|---|---|---|
| 悲观锁 FOR UPDATE | 先锁行再读写，事务结束释放 | **提交幂等**：同一会话并发提交必须强串行 | 持锁期间阻塞，事务要短 |
| 乐观锁 @Version | 提交时比对版本，冲突报错/重试 | PracticeSession 上保留 entityVersion，适合冲突少、读多写少 | 冲突频繁时重试成本高 |
| 数据库唯一键 | 插入时靠唯一索引兜底拒绝重复 | 版本号、题号、错题、答案行的**最终防线** | 只能拒绝，不能合并/排序 |
三者互补：悲观锁管“同一行的检查-写入临界区”，乐观锁管“低冲突更新不互相覆盖”，唯一键在任何并发/绕应用场景下兜底，缺一不可。

---

## External

### E1. 录音复盘。
按 Q2 结构口述 2-3 分钟并录音回听：检查是否把“计划/推断”说成“已实现/已验证”、是否能在被追问时落到具体类与行号、技术取舍是否同时给出替代方案与代价。卡顿点就是要补的知识缺口。

### E2. 简历句子绑定源码/日志。
每条简历表述都要能指向证据，例如：
- “实现提交接口幂等，重复提交返回一致结果” → PracticeService.submit + 并发测试 + HTTP 日志；
- “通过联合唯一索引和事务保证版本/错题不重复” → V1 唯一键 + V2 索引 + EXPLAIN 日志。
写不出证据的句子（如“支撑万级 QPS”）在没有压测前不写。遵循 STAR：情境(S)、任务(T)、行动(A，绑定源码)、结果(R，绑定测试/日志数据)。
