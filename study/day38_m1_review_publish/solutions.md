# Day38 M1 Review & Publish · 题目与标准解答（Solutions）

> 依据：`study.md`（候选审核状态机 + 追加审核记录 + 正式版本不可变）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
对照现有出口：M0 `paper_version` 的 `DRAFT→PUBLISHED`（`BankService.publish`）是正式发布唯一出口；本主题在其**之前**增加候选审核层，审核通过后才创建正式草稿/版本。

---

### Q2. 画状态图。
**候选（导入解析产物）**：
```
CREATED → REVIEWING → ACCEPTED ─→ 创建新 DRAFT paper_version →（既有流程）PUBLISHED
                    └→ REJECTED（带 reason，终态，可重新发起）
```
**正式版本**：`DRAFT → PUBLISHED → ARCHIVED`（归档，不删除、保留历史快照）。
解析只产生候选，**绝不直接改正式题库**；是否进入正式题库由人工审核决定。

---

### Q3. 测非法转换 409。
对 `REJECTED` 候选再执行“通过”、对已 `ACCEPTED` 重复通过、跳过 REVIEWING 直接 ACCEPTED 等，状态机中不存在的边一律返回 409 `STATE_CONFLICT`；actor 无审核权限返回 403；候选不存在返回 404。

---

### Q4. 写出候选状态、版本状态、审核记录字段、版本检查与追加审计规则。
- **候选状态**：CREATED/REVIEWING/ACCEPTED/REJECTED；
- **版本状态**：DRAFT/PUBLISHED/ARCHIVED；
- **审核记录字段（review_record，只追加）**：`id、candidate_id、reviewer_id、decision(ACCEPT/REJECT)、reason、source_version、created_at`；
- **版本检查**：候选/版本实体带 `@Version entity_version`（乐观锁）或审核时悲观行锁，防止旧页面覆盖新决定；
- **追加审计**：每次审核**新增一条记录**，不覆盖上一条意见；撤回/归档/恢复都通过状态转换或新版本表达，绝不 UPDATE 覆盖已发布题面与历史审核意见。

---

### Q5. 写出 `fromState + command + actor -> toState` 转换表。
| fromState | command | actor | toState | 非法情况 |
|---|---|---|---|---|
| CREATED | startReview | 有审核权者 | REVIEWING | 非审核员→403 |
| REVIEWING | accept | 审核员 | ACCEPTED | 其他状态→409 |
| REVIEWING | reject(reason) | 审核员 | REJECTED | reason 必填，否则 400 |
| ACCEPTED | createDraft | 系统/管理员 | 新 DRAFT paper_version | 重复创建→幂等/409 |
| DRAFT | publish | owner/ADMIN | PUBLISHED | 空题→400，非 DRAFT→409 |
| PUBLISHED | archive | ADMIN | ARCHIVED | 已归档→409 |
规则：表中没有的边返回 409；actor 不满足角色/所有权返回 403；转换与审核记录在同一事务落库。

---

### Q6. 构造旧页面覆盖新审核决定的并发冲突。
审核员 A、B 同时打开同一候选（都基于 entityVersion=1）：A 先 accept（提交后 version→2），B 随后提交 reject，其 `WHERE id=? AND entity_version=1` 匹配不到 → 抛乐观锁异常 → 返回 409，提示数据已被他人修改，需要刷新后再决定。这样“旧页面的 stale 决定”不会覆盖新决定；若用悲观锁则 B 在 A 提交前阻塞、之后读到最新状态。

---

## External

### E1. 设计双审核并发。
若规则要求“两人审核通过才录用”，候选上记录 `approvals` 集合或独立审核记录计数：状态转换条件改为“收到两个不同审核员的 ACCEPT 才进入 ACCEPTED”，同一人重复通过只计一次；并发提交靠唯一约束 `(candidate_id, reviewer_id)` 防重复计票，乐观锁保证计数一致；任一 REJECT 按规则进入 REJECTED 或挂起。

### E2. 审计每次转换。
每次状态转换在**同一事务**追加一条不可变审计记录（谁、对哪个候选/版本、什么命令、from→to、原因、时间、requestId），审计表只 INSERT 不 UPDATE/DELETE；可配合 `@TransactionalEventListener(AFTER_COMMIT)` 在事务真正提交后对外发事件，保证审计与业务事实一致、回滚的动作不产生审计。
