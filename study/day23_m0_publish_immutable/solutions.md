# Day23 M0 Publish Immutable · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PaperVersion`（:31 单行 `publish()`）、`BankService.publish`（:64-81）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[PaperVersion.java:6-32](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java)（@Table uk_paper_version_no 约束在 :7、不可变快照字段 :9-17、状态转换 :31）；[BankService.java:64-81](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 发布入口（含同事务 Outbox :75-78、afterCommit 缓存淘汰 :79/:87-98）；[BankController.java:40-44](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) 发布协议（ADMIN 方法授权）。

---

### Q2. 创建 / 发布 / 重复发布。
1. createDraft → PaperVersion `status=DRAFT、publishedAt=null`（PaperVersion:14 默认 DRAFT）；
2. publish（owner、DRAFT、题目非空）→ 200，`paper.publish()`（:72）把 `status=PUBLISHED、publishedAt=Instant.now()`（PaperVersion:31）在同一事务内写库；
3. 对同一 paperId 再 publish → 命中 BankService:69 `if ("PUBLISHED".equals(paper.getStatus())) return paper;`，**返回同一个版本对象**（id/versionNo/publishedAt 均不变），不新建、不刷新时间、不重复写 Outbox，实现幂等。

---

### Q3. 学生发布返回 403。
发布端点 `@PreAuthorize("hasRole('ADMIN')")`（BankController:35），STUDENT 在方法授权阶段被拦截 → 403 `FORBIDDEN`（SecurityConfig.accessDeniedHandler :53-54）；即使同为 ADMIN 但不是该题库 owner，也会在 BankService:68 的 owner 校验处抛 403 FORBIDDEN（双层授权：角色 + 资源归属）。

---

### Q4. 写出 DRAFT→PUBLISHED、status 与 publishedAt 原子更新、新版本替代规则。

真实实体方法（[PaperVersion.java:31](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java)，单行；实体方法不做状态前置判断，“什么状态允许转”由 Service 负责）：
```java
public void publish() { status = "PUBLISHED"; publishedAt = Instant.now(); }
```
Service 层保证只有 DRAFT 能走到这里（BankService:64-81 真实代码）：
```java
PaperVersion paper = papers.findById(paperId).orElseThrow(... NOT_FOUND);          // :66
QuestionBank bank = banks.findById(...).orElseThrow(... NOT_FOUND);                 // :67
if (!bank.getOwnerId().equals(user.userId())) throw forbidden();                    // :68 → 403
if ("PUBLISHED".equals(paper.getStatus())) return paper;                            // :69 幂等
if (!"DRAFT".equals(paper.getStatus())) throw conflict("试卷版本状态不允许发布");     // :70 → 409
if (questions.findByPaperVersionIdOrderByQuestionNo(paperId).isEmpty()) throw bad();// :71 → 400 空卷
paper.publish();                                                                    // :72
PaperVersion result = papers.save(paper);                                           // :73
outboxPublisher.stream().findFirst().ifPresent(p -> p.enqueue(                       // :75-78 同事务写 Outbox
        "paper", result.getId(), "PAPER_PUBLISHED", Map.of(...)));                  //   kafka 未启用时 ObjectProvider 缺省=no-op
evictPublishedAfterCommit();                                                        // :79 注册 afterCommit 淘汰缓存
```
注意缓存淘汰不是事务内直接 evict，而是 `evictPublishedAfterCommit()`（:87-98）：事务同步激活时注册 afterCommit 回调、提交后才 `publishedCache.evict("published")`（:92），无活跃事务才直接 evict（:96）——防止“回滚后缓存已空”或“提交前被旧值回填”。
**新版本替代规则**：已发布版本没有任何“更新题面”的 API；要改题面只能 createDraft 生成新的 PaperVersion（versionNo=max+1，BankService:52-53）再发布。旧版本及其 QuestionVersion 行原样保留，历史练习引用的 version id 永远指向当时题面。

---

### Q5. 列出 404/403/409/400 分支。
校验顺序固定（BankService:66-71，先最确定的错误）：
| 顺序 | 条件（真实行号） | 状态码 | code |
|---|---|---|---|
| 1 | paper 不存在（:66） | 404 | NOT_FOUND |
| 2 | 所属 bank 不存在（:67） | 404 | NOT_FOUND |
| 3 | 当前用户不是 owner（:68） | 403 | FORBIDDEN |
| 4 | 已 PUBLISHED（:69） | 幂等返回（200，同一对象） | — |
| 5 | 非 DRAFT（非法状态，:70） | 409 | STATE_CONFLICT |
| 6 | 题目为空（:71） | 400 | INVALID_INPUT |

---

## External

### E1. 设计撤回 / 归档。
不可变不代表不能下线。建议新增独立状态而不是物理删除：`PUBLISHED → ARCHIVED`（归档，不再出现在“可创建练习”列表，但历史练习仍可查）。撤回要显式端点 + owner/ADMIN 权限 + 状态机校验 + 记录原因和时间；**绝不删除已发布版本行**，保证历史提交仍能追溯题面快照（practice_session 持有的是 paperVersionId 标量外键）。归档对“进行中练习”的影响要显式定义（允许做完还是禁止再提交）。

### E2. 并发发布。
两个发布请求同时读到 DRAFT：无保护时可能都执行 publish。本项目现状的防线是：①状态机检查（:69-70）+ 同事务 UPDATE；②uk_paper_version_no(bank_id, version_no)（PaperVersion:7 / V1:30）保证版本号不重复。要更强可对版本行加 `@Version` 乐观锁或 `SELECT ... FOR UPDATE`，使第二个提交得到 409 或走幂等返回；最终数据库只应有一行 PUBLISHED、publishedAt 唯一。发布成功事件经 Outbox 至少一次投递，消费端用 consumed_event 幂等去重，重复投递不会产生重复副作用。
