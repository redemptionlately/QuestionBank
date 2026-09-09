# Day48 Resume Project Story · 题目与标准解答（Solutions）

> 依据：`study.md` + 本项目真实主链路（BankController/PracticeController、BankService/PracticeService）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankController` 13-52、`PracticeController` 21-45 的 HTTP 主链路；`BankService` 29-67、`PracticeService` 52-85 的事务/锁/事实边界。

---

### Q2. 3/8/15 分钟复述。
- **3 分钟（电梯版）**：一句话定位（面向题库管理与在线练习的模块化 Spring Boot 后端）→ 核心能力（管理员建题库/发布不可变版本，学生练习、服务端确定性判分、错题归集）→ 两个技术亮点（提交幂等用数据库行锁 + 幂等键保证重复提交结果一致；追加式版本 + 联合唯一键保证历史快照可审计）→ 验证方式（9 个集成测试走真实 H2/Flyway，真实 MySQL EXPLAIN 验证索引）。
- **8 分钟（主链路版）**：按 HTTP 请求逐层讲：认证（BCrypt + Bearer 过滤器 + 方法级授权）→ 建题库/草稿（单事务整体回滚）→ 发布状态机（DRAFT→PUBLISHED 幂等、不可变）→ 练习（绑定具体版本快照）→ 提交（行锁串行、canonical 判分、错题聚合、结果 JSON 重放）→ 统一错误契约与观测（ErrorResponse、Actuator、进程内指标/限流/缓存及其边界）。
- **15 分钟（深挖版）**：在 8 分钟基础上对每个难点展开“不变量→并发冲突→失败回滚→替代方案→证据→残余限制”，并主动说明哪些是已验证、哪些只是设计（无 Redis/MQ/对象存储/真实 PDF）。

---

### Q3. 每个数字指出证据。
凡说数字必须绑定测量条件：请求模型、数据规模、并发度、硬件、JDK/软件版本、是否预热、重复次数、统计分位。
- 可说：“集成测试 9 个方法覆盖幂等/回滚/并发/索引”（可指向测试类）；“EXPLAIN 实测该查询 type=ref、rows=2、Backward index scan”（指向 output 日志）；
- 不可说：没有压测就写“支撑 X QPS / 毫秒级响应”。缺测量条件的性能数字没有可比性，应删除。

---

### Q4. 从 HTTP 请求写到 Controller、Service、Repository、SQL、事务提交、响应序列化。
以提交为例完整走一遍：
1. **HTTP**：`POST /api/practices/{id}/submit`，带 `Authorization: Bearer` 与 `Idempotency-Key`；
2. **过滤器链**：RateLimitFilter 计数 → ApiTokenFilter 解析 token 写入 SecurityContext；
3. **Controller**：`@PreAuthorize(STUDENT)` + `@PathVariable/@RequestHeader` 绑定，CurrentUser.require() 取主体；
4. **Service（事务开始）**：校验 key → `findByIdForUpdate`（SELECT … FOR UPDATE 加行锁）→ 校验归属与状态 → 遍历 QuestionVersion 判分 → 写 SubmissionItem/WrongQuestion → session.submit 落总分与结果 JSON；
5. **Repository/SQL**：JPA 生成 SELECT/UPDATE/INSERT，联合唯一键兜底；
6. **事务提交**：代理 commit、释放行锁，数据成为正式事实；
7. **响应序列化**：SubmitResult record 经 Jackson 序列化为 JSON 返回（DTO 不泄露实体内部字段）。
每一步都要能说出输入、输出和失败边界（如 key 缺失 400、越权 403、状态冲突 409）。

---

## External

### E1. 随机源码索引问答。
随机抽一个类/方法，要求 30 秒内说出：它属于哪层、输入输出、依赖谁、失败返回什么、对应哪条测试。抽不出的位置就是掌握盲区，回到源码补齐。

### E2. 删除无日志/无证据数字。
逐条审查简历：把没有测试、日志、EXPLAIN、压测支撑的性能/规模数字删掉或改为可证明表述。最终每条 bullet 都能回答“证据在哪（哪个类/测试/日志）”。区分四类表述：**当前实现（事实）、已验证行为（证据）、尚未实现（缺口）、未来设计（计划）**，不互相替代。
