# Day61 Backend Stack Review · 题目与标准解答（Solutions）

> 全栈总复习收口；每个组件用“五件事”检验，项目只讲可证明事实。

## Current

### Q1. 选一个真实接口做端到端口述与白纸设计（以提交接口为例）。
从外到内完整讲：
1. **HTTP**：`POST /api/practices/{id}/submit`，Bearer 认证 + Idempotency-Key，请求体答案 JSON；
2. **Spring 链**：RateLimitFilter→ApiTokenFilter（写 SecurityContext）→授权→DispatcherServlet 参数绑定与 @Valid→Controller→@PreAuthorize；
3. **事务**：PracticeService 方法开事务，`findByIdForUpdate` 加行锁，校验归属/状态，canonical 判分，写 Item/Wrong/Session，提交时统一 commit（失败整体回滚）；
4. **MySQL**：JPA 生成 SQL，联合唯一键兜底，行锁在提交/回滚时释放，EXPLAIN 验证索引；
5. **缓存/队列边界**：已发布列表读缓存（cache-aside，可重建，事实仍在 DB）；异步导入走持久化任务表 + @Async，非内存队列；
6. **日志指标**：requestId 贯穿、请求/失败/延迟原子计数、health 探针、统一 ErrorResponse。
白纸画出时序图与状态机，并标注每步失败返回什么（400/401/403/404/409/500）。

---

### Q2. 从 Day50-60 各抽一题限时作答并标注不会。
按域各一道（示例，要求闭卷限时）：
- Java(Day50)：写 `? extends/? super` 合法读写并解释 PECS；
- Spring(Day51)：self-invocation 为何让 @Transactional 失效；
- MySQL(Day52)：RC 与 RR 两次读差异 + 行锁；
- Redis/MQ(Day53)：cache-aside 写顺序与 Outbox；
- 安全(Day54)：JWT 校验顺序与 IDOR 修复位置；
- Linux/部署(Day55)：请求超时分层排查命令；
- JVM/性能(Day56)：CPU 高用 jstack/JFR 的证据链；
- 系统设计(Day57)：keyset 分页与稳定排序；
- 微服务(Day58)：两层重试放大倍数；
- 分布式(Day59)：Redis 锁为何还要唯一键/fencing；
- ES(Day60)：text/keyword 区别与 search_after。
答不出的当场标注“不会”，回填对应 Day，不用“看过”冒充掌握。

---

### Q3. 写一页简历项目描述，只用源码/测试/日志能证明的事实。
示例（每条都能指向证据）：
- 基于 Spring Boot 3.4/Java 21 实现题库管理与在线练习模块化单体，覆盖建库、追加式版本发布、练习提交与错题归集闭环；
- 用“行锁 + 幂等键 + 结果快照”实现提交幂等，并发同键重放结果一致，集成测试覆盖幂等/回滚/并发（9 个测试方法，H2+Flyway 真实建表）；
- 为发布列表/学生练习/错题查询建立联合索引，真实 MySQL EXPLAIN 显示命中索引、Backward index scan 无 filesort（附 output 日志）；
- 实现固定窗口限流、进程内 TTL 缓存、原子指标与持久化异步导入任务（事务提交后调度、状态机推进），并明确其单实例边界。
**不写**：未接入的 Redis/Kafka/ES/K8s、没有压测的 QPS/延迟数字；区分“已实现/已测试/仅设计/未掌握”。

---

## External

### E1. 按目标 JD 模拟面试，按八类记录缺口。
八类：Java、Spring、MySQL、Redis、MQ、网络/Linux、算法、项目。每类记录“被问住的具体问题 + 期望答案要点 + 对应 Day”，形成缺口清单，而不是笼统评价“基础薄弱”。

### E2. 缺口回填 + 1/3/7/14 天复习。
把每个缺口挂到对应 Day：当天弄懂并手写一遍 → 第 1 天默写 → 第 3 天做变式 → 第 7 天混合识别 → 第 14 天限时复现。只有能连续扛住追问、亲手改代码并跑测试才算掌握，“看过文档”不计入。

### E3. 是否真正接入中间件：先写预测、验收标准、回滚方案。
若面试反馈指向某中间件（如 Redis），接入前先写三件事：① **预测**：接入后哪个指标/行为应如何变化（如已发布列表读延迟下降、DB QPS 下降多少）；② **验收标准**：可度量的通过条件（缓存命中率、一致性测试、故障切换行为、新增测试通过）；③ **回滚方案**：特性开关/配置一键退回 DB 直读，数据无破坏。接入并验证后才能写进简历，否则仍是“设计理解”。补强顺序：先 Java/Spring/MySQL/算法打牢，再按 JD 选 1-2 个中间件深入，避免同时堆多个未掌握组件。
