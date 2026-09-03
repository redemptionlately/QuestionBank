# 必须会背会写

- 后端项目说明的事实结构是：业务目标、核心实体、请求数据流、事务边界、并发控制、持久化结果和失败响应
- 技术结论必须区分观测事实、基于事实的推断和当前未知；日志字段、SQL 计划和测试结果分别属于不同证据层
- 方案比较至少包含一致性、延迟、吞吐、复杂度、故障恢复和运维成本六个维度
- M0 的源码索引对应关系是：`BankService.java` 负责题库/草稿/发布，`PracticeService.java` 负责会话/判分/幂等，`PracticeSessionRepository.java` 负责悲观锁，`V1__m0_schema.sql` 负责事实结构，`M0FlowIntegrationTest.java` 负责链路证据
- 一次提交的因果链是：`Idempotency-Key -> findByIdForUpdate -> 当前学生校验 -> Session 状态 -> 遍历 QuestionVersion -> 写 SubmissionItem/WrongQuestion -> Session.submit -> JSON 重放`
- 同一事实可用不同证据验证：表约束验证永久结构，事务测试验证同成败，HTTP E2E 验证协议，EXPLAIN 验证当前查询计划；没有任何一项单独证明全部系统性质
- 源码索引（会背会写）：[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第 31-60 行、[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 52-85 行；这两段分别是项目最核心的草稿/发布和提交/幂等代码

# 必须理解

- “工程验证通过”表示当前输入和环境下路径满足断言；“掌握”还涉及实现细节、边界条件和替代方案的因果解释
- 失败分析的最小闭环是现象、复现条件、根因、修复、回归证据和残余限制
- 源码索引（必须理解）：[M0FlowIntegrationTest.java](../../src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 151-210 行的回滚/并发/索引证据；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 49-86 行的持久化边界
- 项目：[README.md](../../README.md)、[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java)、[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java)、[M0FlowIntegrationTest.java](../../src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java)；官方：[STAR](https://www.indeed.com/career-advice/interviewing/how-to-use-the-star-interview-response-technique)
