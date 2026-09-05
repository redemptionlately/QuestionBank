# MustRemember

- REST 命令对应明确状态转换：创建草稿产生 `DRAFT`，发布产生 `PUBLISHED`，创建练习产生 `IN_PROGRESS`，提交产生 `SUBMITTED`
- 发布版本采用追加式模型；历史练习通过 `paper_version_id` 引用具体快照，已发布题面没有更新路径
- 模块化单体按 `entity/repository/service/controller/common/auth` 分包；Controller 不直接访问 Repository，跨模块调用通过 Service，事务仍在同一进程和数据库内
- 包边界代码形态是：
  ```text
  auth: Authentication、token、当前用户
  bank: QuestionBank、PaperVersion、QuestionVersion、发布
  practice: PracticeSession、SubmissionItem、判分、幂等、错题
  common: ApiException、ErrorResponse、CurrentUser
  ```
- 源码索引（MustRemember）：[BankController.java](../../src/main/java/com/allen/questionbank/controller/BankController.java) 第 22-53 行的题库/发布/查询接口；[PracticeController.java](../../src/main/java/com/allen/questionbank/controller/PracticeController.java) 第 25-48 行的练习接口

# MustUnderstand

- JPA 适合聚合和事务边界清晰的 CRUD；复杂报表、锁定读取和执行计划分析需要显式 JPQL/SQL
- 内存 token 只适合单实例教学环境；多实例认证需要共享 Session、签名 Token 或集中式 Token 存储
- 新组件的工程边界由数据所有权、失败语义、降级路径、观测指标和测试口径定义，而不由“技术流行度”定义
- 源码索引（MustUnderstand）：[题库平台项目蓝图](../../../AI_Infra/Phases_book/题库平台项目蓝图.md) 的 M0/M1/M2 边界；[README.md](../../README.md) 的 Scope 与 Verification
- 设计依据：[README.md](../../README.md)、[题库平台项目蓝图](../../../AI_Infra/Phases_book/题库平台项目蓝图.md)；Agent 接入边界：[Java_Agent学习与接入计划](../../../AI_Infra/Phases_book/Java_Agent学习与接入计划.md)
