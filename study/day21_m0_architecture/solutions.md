# Day21 M0 Architecture · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实包结构 auth/bank/practice/common/importjob。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankController` 13-52 行、`PracticeController` 13-45 行接口入口与 DTO 投影；`BankService` 29-106 行、`PracticeService` 33-160 行模块职责。

---

### Q2. 不看源码画出登录到提交的时序。

```
学生端                auth                bank                practice             MySQL
 │ POST /api/auth/login │                    │                    │                    │
 │─────────────────────▶│ AuthService.login  │                    │                    │
 │                      │ BCrypt.matches+issue token              │                    │
 │◀──── token ──────────│                    │                    │                    │
 │ (管理员) POST admin/banks/versions/publish │                   │                    │
 │──────────────────────────────────────────▶│ BankService 校验/状态机                  │
 │◀──────────────── PUBLISHED paperId ───────│                    │                    │
 │ POST /api/practices {paperVersionId}      │                    │                    │
 │───────────────────────────────────────────┼───────────────────▶│ requirePublished   │
 │◀──────────────── sessionId(IN_PROGRESS) ──┴────────────────────│                    │
 │ PUT /practices/{id}/answers/{qid} (多次)  │                    │ saveAnswer/UPSERT  │
 │───────────────────────────────────────────────────────────────▶│                    │
 │ POST /practices/{id}/submit + Idempotency-Key                  │ submit 行锁/判分   │
 │───────────────────────────────────────────────────────────────▶│ Item+Session+Wrong │
 │◀──────── SubmitResult(total/max/answers) ──────────────────────│                    │
```

---

### Q3. 列出每步输入、输出、事务、失败码。

| 步骤 | 输入 | 输出 | 事务 | 主要失败码 |
|---|---|---|---|---|
| login | username/password | token | 无（只读） | 401 AUTH_INVALID |
| createBank | name/description | BankResponse | 写 | 400/403 |
| createDraft | title+questions | PaperResponse(DRAFT) | 整卷一个事务 | 400 VALIDATION/INVALID、403、404 |
| publish | paperId | PUBLISHED+publishedAt | 状态原子更新 | 404/403/409/400 |
| create practice | paperVersionId | session(IN_PROGRESS) | 写 | 409 未发布 |
| saveAnswer | questionId+answer JSON | Item | 写 | 404/409/400/403 |
| submit | Idempotency-Key | SubmitResult | Item+Session+Wrong 同事务 | 400 key、409 冲突 |

---

### Q4. 写出 Auth、Bank、Practice 三模块边界与 Controller→Repository 调用方向。
- **auth**：登录、令牌签发/解析、当前用户（`CurrentUser`/`AuthPrincipal`）、安全配置，拥有 user_account 数据；
- **bank**：题库 QuestionBank、版本 PaperVersion、题目 QuestionVersion 的创建/发布/查询，是“题面事实”的所有者；
- **practice**：练习会话、答案、判分、幂等、错题，是“作答事实”的所有者；
- **common**：ApiException、ErrorResponse、CurrentUser、缓存、限流、指标等横切设施。
调用方向严格为 `Controller → Service → Repository → MySQL`；Controller 不碰 Repository，Repository 不做授权，Entity 只维护自身状态不变量；practice 需要题面时调用 bank 的 Service（`requirePublished`），不反向依赖。

---

### Q5. 画出两个请求经过三模块的依赖箭头，标出 MySQL 事实来源。
```text
请求A（管理员发布）：
CurrentUser(auth) → BankController → BankService → Paper/Question/Bank Repository → MySQL(事实)
                                    └→ publishedCache(common, 派生缓存，可失效重建)

请求B（学生提交）：
CurrentUser(auth) → PracticeController → PracticeService ─→ BankService.requirePublished/读标准答案
                                        ├→ Practice/Submission/Wrong Repository → MySQL(事实)
                                        └→ 返回 SubmitResult（DTO 投影）
```
**MySQL 是唯一正式事实来源**；HTTP 响应是 DTO 投影（不暴露实体内部字段）；`submission_result_json` 是提交结果快照，用于幂等重放返回字节一致的结果；内存缓存/指标都是派生、易失数据。

---

## External

### E1. 设计审计事件。
在关键状态转换（发布、提交、登录失败、越权拒绝）产出审计事件：谁（userId）、对什么资源（类型+id）、动作、前后状态、requestId、时间、结果。实现选择：同事务写审计表（强一致但增加事务负担）或事务提交后发事件（`@TransactionalEventListener(AFTER_COMMIT)`，只有真正提交成功才记录，避免回滚动作被审计）。审计只追加、不可改，不含密码/token 明文。

### E2. 对照 verified / learned。
- **verified（已验证）**：有源码 + 自动化测试证据，如三题型判分、幂等重放、回滚、索引存在、角色拦截；
- **learned（仅学习/推断）**：知道原理但本项目未验证，如真实 MySQL 执行计划、Redis 多实例一致性、分布式事务、压测性能数字。
简历和复盘只陈述 verified 事实，learned 内容必须标注“未在本项目验证”，不把理论当成果。
