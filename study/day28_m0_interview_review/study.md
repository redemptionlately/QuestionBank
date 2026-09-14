# MustRemember

- 后端项目说明的事实结构是：业务目标、核心实体、请求数据流、事务边界、并发控制、持久化结果和失败响应
- 技术结论必须区分四态：默认测试已验证（90 法）、环境门控证据（29 法 *_EVIDENCE）、仅设计未实现、cloud/ 独立微服务演示；日志字段、SQL 计划和测试结果分别属于不同证据层
- 方案比较至少包含一致性、延迟、吞吐、复杂度、故障恢复和运维成本六个维度
- M0 的源码索引对应关系是：`BankService.java`（草稿 :44-62/发布 :64-81）负责题库/草稿/发布/Outbox/缓存淘汰，`PracticeService.java`（提交 :56-89）负责会话/判分/幂等/错题，`PracticeSessionRepository.java:14-16` 负责悲观锁，V1~V4 SQL 负责 11 张表事实结构，27 个测试类负责分层证据
- 一次提交的因果链是：`Idempotency-Key ->（可选 Redis 锁守卫）-> findByIdForUpdate -> 当前学生校验 -> Session 状态 -> 同 key 重放/异键 409/遍历 QuestionVersion 判分 -> 写 SubmissionItem/WrongQuestion -> Session.submit -> 提交释放锁 -> JSON 重放`
- 同一事实可用不同证据验证：表约束验证永久结构，事务测试验证同成败，HTTP E2E 验证协议，EXPLAIN 验证当前查询计划；没有任何一项单独证明全部系统性质
- 源码索引（MustRemember）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 44-81 行、[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 56-89 行；这两段分别是项目最核心的草稿/发布和提交/幂等代码

# MustUnderstand

- “工程验证通过”表示当前输入和环境下路径满足断言；“掌握”还涉及实现细节、边界条件和替代方案的因果解释；门控测试默认跳过不等于失败，也不等于已验证
- 失败分析的最小闭环是现象、复现条件、根因、修复、回归证据和残余限制
- 并发四层各有位置：Redis 锁（跨实例减压、可开关）→ 悲观行锁（同行检查-写入临界区）→ @Version 乐观锁（低冲突更新）→ 唯一键（任何路径的最终兜底），绕过上层时下层必须仍保正确
- 源码索引（MustUnderstand）：[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 154-220 行的回滚/并发/索引证据（全类 8 法）；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 49-86 行的持久化边界
- 项目：[README.md](../../README.md)、[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java)、[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java)、[resume_one_page.md](../../docs/interview/resume_one_page.md)（含“明确不写”清单）；官方：[STAR](https://www.indeed.com/career-advice/interviewing/how-to-use-the-star-interview-response-technique)
