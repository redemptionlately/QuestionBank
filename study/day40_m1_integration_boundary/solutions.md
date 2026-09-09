# Day40 M1 Integration Boundary · 题目与标准解答（Solutions）

> 依据：`study.md`（M1 目标数据流、领域事件 envelope、最终一致性）。当前 M0 worker 仅推进 sourceName 任务状态，M1 数据流是目标设计而非现有实现。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
当前事实锚点：`ImportJobWorker`（RECEIVED→SUCCEEDED 占位）、M0 `paper_version` 正式发布出口。目标设计：file metadata → persistent job → text extraction → candidate → review → new paper_version。

---

### Q2. 画 happy path / 失败分支。
**Happy path**：
```
上传文件 → 建 import job(RECEIVED,持久化) → afterCommit 调度 worker
  → 文本抽取(PARSING) → 生成 candidate(CREATED→REVIEWING)
  → 人工审核 ACCEPTED → 创建新 DRAFT paper_version → publish(PUBLISHED)
```
**失败分支**：抽取失败 → job FAILED（公开 error，可重试/死信）；候选审核 REJECTED → 终止并记录原因；创建版本失败 → 候选保留、不产生版本，可重试；任何阶段失败都不改已发布版本。

---

### Q3. 明确“未实现不能进简历”。
简历/复盘只能写有源码与测试证据的能力：当前可写“持久化异步任务状态机（RECEIVED/PROCESSING/SUCCEEDED/FAILED）、afterCommit 调度、资源隔离查询”。文本抽取、candidate、审核、对象存储、事件驱动集成都**未实现**，不能写成“做过 PDF 智能导入/事件驱动架构”，最多写“设计了……方案”。这是 verified 与 learned 的边界。

---

### Q4. 写出事件 eventType/eventVersion/aggregateId/occurredAt/幂等标识。
| 字段 | 作用 |
|---|---|
| eventType | 事件类型（如 PaperDraftCreated、CandidateAccepted） |
| eventVersion | 事件 schema 版本，支持消费者兼容演进 |
| aggregateId | 所属聚合 id（如 paper-123 / job-45） |
| occurredAt | 事件发生时间（ISO-8601，UTC） |
| producer | 产生事件的服务/模块 |
| idempotencyKey/eventId | 事件唯一标识，供消费者去重 |
| payload | 事件数据（版本化结构） |

envelope JSON：
```json
{"eventType":"PaperDraftCreated","eventVersion":1,"aggregateId":"paper-123",
 "occurredAt":"2026-08-19T00:00:00Z","producer":"importjob",
 "idempotencyKey":"evt-123","payload":{}}
```

---

### Q5. 说明消费者重复、乱序、死信处理。
- **重复（默认会发生，至少一次投递）**：消费者维护“已处理事件 id”表或用业务唯一键，处理前先判重，重复事件直接 ack，效果与只消费一次相同（幂等）；
- **乱序**：事件带 occurredAt/版本号，消费者比较聚合当前版本，过期事件丢弃或记录，不允许旧事件覆盖新状态；
- **处理失败**：可恢复错误退避重试，超过次数进**死信队列**并告警，支持人工修正后重放；
- 消费者要可重放：重跑历史事件能得到相同最终状态。

---

### Q6. 画出 M0 发布出口与 M1 导入候选之间的事务边界。
```
[导入模块事务]                      [审核模块事务]              [题库模块事务]
job/candidate 状态推进   ──事件──▶  审核决定(追加记录)  ──事件──▶  创建 DRAFT → publish
（各自独立短事务，不用一个跨模块长事务强绑定）
```
- 跨模块**不用一个分布式长事务**强绑定，而是各自本地事务 + 追加领域事件 + 补偿任务表达**最终一致性**；
- M0 `paper_version` 是唯一正式发布出口：导入模块只能产出 candidate 或新 DRAFT，**绝不直接修改已发布题面**；
- 事件可在本地事务同写 outbox 表（事务发件箱），afterCommit 后投递，避免“业务回滚但事件已发”。

---

## External

### E1. 设计事件 schema/version。
每个 eventType 定义带版本的 schema（字段、类型、必填、兼容规则）：新增可空字段为兼容变更（eventVersion 不变或小版本），改语义/删字段为破坏性变更需升 eventVersion；消费者按 version 反序列化，老消费者遇到未知高版本要安全忽略或进入死信，不得崩溃。Schema 集中登记并随代码评审。

### E2. 模拟重复乱序。
测试向消费者连续投递：同一 eventId 两次（断言只产生一次副作用）、乱序的 v1/v2/v3（断言最终以最高版本/最新 occurredAt 为准，旧版本被忽略）、一条毒消息（断言重试后进死信且不阻塞后续消费）。用消费记录表断言幂等与顺序处理的正确性。
