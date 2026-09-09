# Day14 M0 Variants · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实模块划分（auth/bank/practice/common/importjob）与 README Scope。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankController` 13-52 行题库/发布/查询接口；`PracticeController` 21-45 行练习接口；包结构 `auth / bank / practice / common / importjob`。

---

### Q2. 比较 JPA 与 MyBatis 的职责、优点和当前选择理由。

| 维度 | Spring Data JPA | MyBatis |
|---|---|---|
| 抽象层级 | ORM，实体/对象映射，Repository 接口自动生成 CRUD | 半自动 ORM，SQL 与 Java 方法绑定，SQL 自己写 |
| 优势 | 聚合 CRUD、级联、脏检查、事务边界清晰、样板代码少 | SQL 完全可控、复杂多表/动态 SQL/调优直观 |
| 劣势 | 复杂报表、批量更新、N+1 需要显式 JPQL/EntityGraph 管控 | 简单 CRUD 也要写 SQL，对象关系要自己拼 |
| 适用 | 聚合边界清晰、以实体为中心的事务型业务（本题库） | 报表统计、复杂查询占主导的系统 |

**本项目选 JPA 的理由**：M0 是典型按聚合操作的事务系统（题库→版本→题目、会话→答案→错题），CRUD 与状态机为主，JPA + 方法名派生查询/少量 `@Query` 足够；锁定读取用 `@Lock(PESSIMISTIC_WRITE)` 显式声明，报表类需求未来再引入 SQL/MyBatis，不提前增加复杂度。

---

### Q3. 说出 M0、M1、M2、M3 的边界，不把未来计划说成已实现。
- **M0（已实现）**：登录鉴权、题库/草稿/不可变发布、学生练习、确定性判分、错题、Flyway、统一错误、Docker MySQL、固定窗口限流、进程内 TTL 缓存、原子请求指标、持久化异步导入任务（RECEIVED→PROCESSING→SUCCEEDED/FAILED）。
- **M1（增量）**：真实文件上传、异步导入流水线、对象存储、审核发布、集成分界（对应 day36-40 主题，尚未全部落地为生产级实现）。
- **M2（增量）**：幂等记录通用化、任务重试、限流/指标/日志的生产化、负载测试、故障注入（day30-35）。
- **M3（规划）**：分布式缓存/消息、租约恢复、Prometheus、链路追踪、Agent 等。
表述纪律：只把“有源码+测试证据”的称为已实现，其余一律称为计划/增量。

---

### Q4. 写出引入 Redis 或 Agent 前必须回答的四个问题。
1. **具体问题**：它解决哪个可量化的问题（如多实例共享缓存/限流）？没有明确痛点不引入。
2. **现有方案不足**：进程内 `ExpiringCache`/固定窗口在多实例下具体哪里不满足（数据不共享、重启丢失）？用数据或场景证明。
3. **失败降级**：中间件不可用时系统如何退化（本地缓存兜底/直接查库/拒绝请求），故障是否扩散？
4. **测试方法**：如何验证收益与降级正确（集成测试、故障注入、压测对比指标），回滚方案是什么？
新组件的边界由数据所有权、失败语义、降级路径、观测指标、测试口径定义，而不是技术流行度。

---

### Q5. 给出一个 M0 代码小变式并说明需要修改的迁移、Service 和测试。
示例变式：给 `QuestionBank` 增加“是否公开”字段 `visible`。
- **迁移**：新增 `V4__bank_visible.sql`：`ALTER TABLE question_bank ADD COLUMN visible BOOLEAN NOT NULL DEFAULT TRUE;`（不改 V1）；
- **实体/DTO**：`QuestionBank` 加字段与 getter；`CreateBankRequest/BankResponse` 加可空字段；
- **Service**：`createBank` 写入该字段，`published()` 查询按需加 `findByStatus...` 过滤条件；
- **测试**：补默认值、私有题库不出现在 published 列表两个用例；`ddl-auto=validate` 要求迁移与实体同步，否则启动失败。
原则：结构变更走新版本迁移，实体、DTO、业务规则、测试四处同步。

---

### Q6. 为草稿创建、版本发布、练习提交写出资源状态转换和模块调用方向。

| 动作 | 状态转换 | 模块调用方向 |
|---|---|---|
| 创建草稿 | （无）→ PaperVersion `DRAFT`，题目版本落库 | controller(Bank) → service(Bank) → repository(bank) |
| 版本发布 | `DRAFT → PUBLISHED`，写 publishedAt，失效缓存 | controller(Bank) → service(Bank) → repository；practice 侧只读引用 |
| 创建练习 | （无）→ PracticeSession `IN_PROGRESS`（要求版本 PUBLISHED） | controller(Practice) → service(Practice) → **BankService.requirePublished**（跨模块只通过 Service）→ repository |
| 提交练习 | `IN_PROGRESS → SUBMITTED`（终态） | controller(Practice) → service(Practice) → bank 读取标准答案 + practice 写答案/错题 |

模块规则：Controller 不直接访问 Repository；跨模块（practice 用 bank 的数据）只能调用对方 Service。

---

## External

### E1. 将判分规则改为多选部分得分，列出数据模型、事务和兼容性影响（不实现）。
- 数据模型：题目可能需要“漏选得分比例/计分策略”字段，`SubmissionItem` 要能表达 partial 状态（correct 布尔不够，需要枚举或 partial 标志）；
- 判分事务：canonical 比较从“集合相等”改为交集/差集计算，maxScore 与 totalScore 语义变化；
- 兼容性：历史提交结果 JSON 是旧规则产物，需要版本化判分规则或重算策略；测试要新增少选/多选/全对/全错矩阵；API 响应可能新增 grading 字段（向后兼容）。属于规则级变更，必须先设计再实现。

### E2. 将内存 token 替换为持久会话，画出表结构、撤销流程和重启行为。
- 表：`auth_token(token PK, user_id, issued_at, expires_at, revoked BOOLEAN, ...)`；
- 登录插入一行并返回不透明 token；过滤器改为查库（可加本地短 TTL 缓存）；
- 撤销：登出/管理员封禁把 revoked 置真（或删行），过滤器立即拒绝；
- 重启行为：会话不丢失（区别于现在 ConcurrentHashMap 重启失效）；多实例共享同一张表。代价是每次请求查库，需要缓存与清理过期 token 的定时任务。

### E3. 写一页 M0 复盘：事实、推断、限制、下一步，不写未经测试的性能结论。
- **事实**：只写源码与测试可证明的内容（接口清单、9 个测试覆盖点、状态机）；
- **推断**：标注为推断（如“行锁足以支撑低并发提交”，未经压测不能写成结论）；
- **限制**：单进程缓存/限流/指标、内存 token、无真实 PDF 等；
- **下一步**：按 E4 问的四问题决定是否引入 Redis/MQ，并用压测/故障注入验证。
