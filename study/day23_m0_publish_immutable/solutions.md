# Day23 M0 Publish Immutable · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PaperVersion`（第 31 行 `publish()`）、`BankService.publish`（55-67 行）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PaperVersion` 11-17 行不可变快照字段、31 行状态转换；`BankService` 55-67 行发布入口；`BankController` 34-38 行发布协议。

---

### Q2. 创建 / 发布 / 重复发布。
1. createDraft → PaperVersion `status=DRAFT、publishedAt=null`；
2. publish（owner、DRAFT、题目非空）→ 200，`status=PUBLISHED、publishedAt=Instant.now()`；
3. 对同一 paperId 再 publish → 命中 `if ("PUBLISHED".equals(status)) return paper;`，**返回同一个版本对象**（id/versionNo/publishedAt 均不变），不新建、不刷新时间，实现幂等。

---

### Q3. 学生发布返回 403。
发布端点 `@PreAuthorize("hasRole('ADMIN')")`，STUDENT 在方法授权阶段被拦截 → 403 `FORBIDDEN`；即使同为 ADMIN 但不是该题库 owner，也会在 Service 的 owner 校验处被 403。

---

### Q4. 写出 DRAFT→PUBLISHED、status 与 publishedAt 原子更新、新版本替代规则。

真实实体方法（`PaperVersion.java:31`）：
```java
public void publish() {            // 状态前置判断在 Service 层完成，实体方法只负责“如何转换”
    status = "PUBLISHED";
    publishedAt = Instant.now();   // 状态与时间在同一事务内一起写入 → 原子事实
}
```
Service 层保证只有 DRAFT 能走到这里（真实代码）：
```java
if ("PUBLISHED".equals(paper.getStatus())) return paper;     // 幂等
if (!"DRAFT".equals(paper.getStatus())) throw conflict(...); // 非法状态 409
if (questions...isEmpty()) throw bad(...);                   // 空版本 400
paper.publish();
papers.save(paper);
publishedCache.evict("published");
```
**新版本替代规则**：已发布版本没有任何“更新题面”的 API；要改题面只能 createDraft 生成新的 PaperVersion（versionNo+1）再发布。旧版本及其 QuestionVersion 行原样保留。

---

### Q5. 列出 404/403/409/400 分支。
校验顺序固定（先最确定的错误）：
| 顺序 | 条件 | 状态码 | code |
|---|---|---|---|
| 1 | paper 不存在 | 404 | NOT_FOUND |
| 2 | 所属 bank 不存在 | 404 | NOT_FOUND |
| 3 | 当前用户不是 owner | 403 | FORBIDDEN |
| 4 | 已 PUBLISHED | 幂等返回（200） | — |
| 5 | 非 DRAFT（非法状态） | 409 | STATE_CONFLICT |
| 6 | 题目为空 | 400 | INVALID_INPUT |

---

## External

### E1. 设计撤回 / 归档。
不可变不代表不能下线。建议新增独立状态而不是物理删除：`PUBLISHED → ARCHIVED`（归档，不再出现在“可创建练习”列表，但历史练习仍可查）。撤回要显式端点 + owner/ADMIN 权限 + 状态机校验 + 记录原因和时间；**绝不删除已发布版本行**，保证历史提交仍能追溯题面快照。归档对“进行中练习”的影响要显式定义（允许做完还是禁止再提交）。

### E2. 并发发布。
两个发布请求同时读到 DRAFT：无保护时可能都执行 publish（结果虽同为 PUBLISHED，但 publishedAt/缓存处理可能不一致）。稳妥做法：对版本行加 `@Version` 乐观锁或 `SELECT ... FOR UPDATE`，使第二个提交得到 409 或走幂等返回；最终数据库只应有一行 PUBLISHED、publishedAt 唯一。状态机 + 事务 + 约束共同保证“恰好发布一次”。
