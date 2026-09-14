# Day06 Publish Transaction · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `BankService.publish`、`PaperVersion.publish()`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（主代码在 `app/src/main/java/com/allen/questionbank/`）：[bank/BankService.java:64-81](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) publish 发布方法（存在性/所有权/状态/非空校验顺序 :66-71，状态迁移与 save :72-73，同事务 Outbox :74-78，afterCommit 缓存淘汰注册 :79）、`:87-98` evictPublishedAfterCommit；[bank/PaperVersion.java:14-17](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 状态与时间字段、`:31` publish() 状态转换方法。

---

### Q2. 写出发布的前置状态、成功状态和冲突状态。

**状态机只有一条合法迁移：`DRAFT → PUBLISHED`**。真实代码：
```java
// 实体内部聚状态转换，保证不被外部随意 set
public void publish() {
    status = "PUBLISHED";
    publishedAt = Instant.now();
}

@Transactional
public PaperVersion publish(AuthPrincipal user, Long paperId) {
    PaperVersion paper = papers.findById(paperId).orElseThrow(() -> notFound("试卷版本不存在"));
    QuestionBank bank = banks.findById(paper.getBankId()).orElseThrow(() -> notFound("题库不存在"));
    if (!bank.getOwnerId().equals(user.userId())) throw forbidden();        // 所有权
    if ("PUBLISHED".equals(paper.getStatus())) return paper;                // 幂等：重复发布返回原版本
    if (!"DRAFT".equals(paper.getStatus())) throw conflict("试卷版本状态不允许发布"); // 非法状态→409
    if (questions.findByPaperVersionIdOrderByQuestionNo(paperId).isEmpty())
        throw bad("试卷不能发布为空版本");                                    // 空版本→400
    paper.publish();
    PaperVersion result = papers.save(paper);
    // Outbox 与发布在同一事务落库（:74-78）；app.kafka.enabled!=true 时 ObjectProvider 为空，整段 no-op
    outboxPublisher.stream().findFirst().ifPresent(publisher -> publisher.enqueue(
            "paper", result.getId(), "PAPER_PUBLISHED",
            Map.of("paperId", result.getId(), "bankId", result.getBankId(),
                    "title", result.getTitle(), "publishedAt", String.valueOf(result.getPublishedAt()))));
    evictPublishedAfterCommit();   // 注意：缓存淘汰注册到事务提交之后，不是当场 evict
    return result;
}

// 缓存淘汰必须在提交之后（BankService.java:87-98）：事务内淘汰会被并发请求在提交前回写旧值
private void evictPublishedAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publishedCache.evict(PUBLISHED_KEY); }
        });
    } else {
        publishedCache.evict(PUBLISHED_KEY);   // 无活跃事务（一般不会走到）才直接淘汰
    }
}
```
| 状态 | 条件 | 结果 |
|---|---|---|
| 前置 | 必须是 DRAFT、属于当前用户、至少一道题 | 允许发布 |
| 成功 | 调实体 `publish()`，同事务写 status=PUBLISHED + publishedAt | 200 |
| 冲突 | 非 DRAFT（不存在“撤回”路径） | 409 `STATE_CONFLICT` |
| 幂等 | 已是 PUBLISHED 再次发布 | 直接返回同一版本，不新建 |

---

### Q3. 含题目草稿发布成功，状态变成 `PUBLISHED`。
`POST /api/admin/versions/{paperId}/publish`（ADMIN token）→ 200，响应 `status:"PUBLISHED"` 且带 `publishedAt`；之后学生可通过 `GET /api/papers/published`、`GET /api/papers/{id}` 看到（requirePublished 只放行 PUBLISHED）。

---

### Q4. 空版本不能发布，非所有者不能发布。
- **空版本**：`questions.findByPaperVersionId...isEmpty()` 为真 → 400 `INVALID_INPUT`「试卷不能发布为空版本」；
- **非所有者**：题库 ownerId 与当前用户不等 → 403 `FORBIDDEN`；
- **版本不存在** → 404 `NOT_FOUND`。
校验顺序固定为：存在性 → 所有权 → 状态 → 内容非空，先返回最确定的错误。

---

### Q5. 重复发布返回同一版本，不生成第二个版本。
代码中 `if ("PUBLISHED".equals(status)) return paper;`（:69）实现**幂等**：在所有写入之前提前返回，不新建 PaperVersion、不刷新 publishedAt、不再写 Outbox、不注册缓存淘汰。测试上对同一 paperId 调两次 publish，返回体 id 与第一次完全相同。

---

### Q6. 解释发布事务失败时应保持哪些数据不变。
发布是一个 `@Transactional` 事务（:64），方法内任何未捕获 RuntimeException 都会让事务回滚：
- `paper_version.status` 仍为 DRAFT，`published_at` 仍为 NULL（不会出现“状态改了但时间没写”的半发布）；
- 同事务写入的 `outbox_event` 行一并回滚，不会出现“库回滚了但事件已入箱”；
- 不会产生新的版本行、题目行；
- **缓存淘汰注册在 afterCommit 回调里（:87-98），事务回滚则 afterCommit 根本不触发，缓存不会被错误清空**——这正是不能在事务内直接 evict 的原因：事务内淘汰后，并发读可能在提交前把旧值重新写回缓存。该时序由 `RedisCacheIntegrationTest.publishEvictsCacheOnlyAfterCommit`（:74）锁死。
原则：状态、状态时间、Outbox 事件必须在同一事务内一起落库，缓存失效放在提交之后，要么全部成功要么全部不变。

---

## External

### E1. 在发布事务中注入异常，确认没有半发布状态。
在 `paper.publish()` 之后、`save` 时（或 Outbox enqueue 序列化失败时）人为抛异常，断言：事务回滚后数据库该行仍是 DRAFT、publishedAt 为 NULL、outbox_event 无对应行，afterCommit 回调不触发、缓存不被清空，`GET /api/papers/published` 列表不含它。草稿侧的整笔回滚由 `M0FlowIntegrationTest.invalidQuestionRollsBackTheWholeDraftTransaction`（:154）固化；回滚规则本身由 TransactionFailureModeTest 5 个用例覆盖（RuntimeException 回滚、checked 异常默认不回滚、需要 `rollbackFor` 才纳入回滚等）。

### E2. 并发发布同一版本，记录两个响应和最终状态。
两个请求同时进入：都读到 DRAFT，先后提交。理想结果：一个 200 完成发布，另一个因状态已变/乐观锁得到 409 或走幂等分支返回同一 PUBLISHED 版本；最终数据库只有一行、status=PUBLISHED。要严格防并发覆盖，可对行加 `@Version` 乐观锁或 `SELECT ... FOR UPDATE`。

### E3. 直接尝试修改已发布题目，确认产品层面没有修改 API。
版本不可变：项目没有提供“编辑 PUBLISHED 版本题面/答案”的端点；要改内容只能创建新草稿版本再发布。这样历史练习引用的 `paper_version_id` 永远对应固定快照，保证做过的卷子题面和判分依据不被事后篡改。
