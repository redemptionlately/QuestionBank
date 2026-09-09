# Day06 Publish Transaction · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `BankService.publish`、`PaperVersion.publish()`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankService` 55-67 行发布方法（owner/status/非空校验顺序）；`PaperVersion` 14-17 行状态与时间字段、31 行状态转换方法。

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
    publishedCache.evict("published");                                      // 失效已发布列表缓存
    return result;
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
代码中 `if ("PUBLISHED".equals(status)) return paper;` 实现**幂等**：不新建 PaperVersion、不刷新 publishedAt、不重复 evict 之外的写入。测试上对同一 paperId 调两次 publish，返回体 id 与第一次完全相同。

---

### Q6. 解释发布事务失败时应保持哪些数据不变。
发布是一个 `@Transactional` 事务，方法内任何未捕获 RuntimeException 都会让事务回滚：
- `paper_version.status` 仍为 DRAFT，`published_at` 仍为 NULL（不会出现“状态改了但时间没写”的半发布）；
- 不会产生新的版本行、题目行；
- 缓存 evict 若在回滚前执行影响有限（下次查询会重新加载），因此把 `evict` 放在 save 之后、且以数据库为准。
原则：状态与状态时间必须在同一事务内一起落库，要么全部成功要么全部不变。

---

## External

### E1. 在发布事务中注入异常，确认没有半发布状态。
在 `paper.publish()` 之后、`save` 之前（或 save 时）人为抛异常，断言：事务回滚后数据库该行仍是 DRAFT、publishedAt 为 NULL，`GET /api/papers/published` 列表不含它。这就是 `@Transactional` 默认对 RuntimeException 回滚的验证（rollback 规则；checked 异常默认不回滚，需要 `rollbackFor` 指定）。

### E2. 并发发布同一版本，记录两个响应和最终状态。
两个请求同时进入：都读到 DRAFT，先后提交。理想结果：一个 200 完成发布，另一个因状态已变/乐观锁得到 409 或走幂等分支返回同一 PUBLISHED 版本；最终数据库只有一行、status=PUBLISHED。要严格防并发覆盖，可对行加 `@Version` 乐观锁或 `SELECT ... FOR UPDATE`。

### E3. 直接尝试修改已发布题目，确认产品层面没有修改 API。
版本不可变：项目没有提供“编辑 PUBLISHED 版本题面/答案”的端点；要改内容只能创建新草稿版本再发布。这样历史练习引用的 `paper_version_id` 永远对应固定快照，保证做过的卷子题面和判分依据不被事后篡改。
