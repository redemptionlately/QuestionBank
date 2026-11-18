# MustRemember

- Auth、Bank、Practice 三个业务模块分别围绕用户认证、题库版本、练习聚合；调用方向是 `Controller -> Service -> Repository -> MySQL`，由 ArchitectureTest 两条规则（Controller 禁摸 Repository、跨域禁摸对方 Repository）做成可执行约束
- 每个请求都同时受到身份边界、资源边界、状态边界和数据边界约束；通过角色不代表拥有具体 bank/session（Service 内再查 owner/student）
- MySQL 是正式事实来源，HTTP 响应是 DTO record 投影，`submission_result_json` 保存提交结果快照用于幂等重放；Redis 缓存/锁是派生与协调数据，DB 行锁+唯一约束才是最终兜底
- 重构后 app 主代码 9 包 68 类：auth 10 / bank 11 / practice 11（JPA 写入 + MyBatis 错题只读）/ common 12 / cache 3（PublishedPaperCache 双实现）/ event 8（Kafka Outbox）/ redis 3（锁+令牌桶限流）/ importjob 7 / ops 2
- M0 模块依赖骨架是：
  ```text
  auth.CurrentUser -> bank.BankController -> bank.BankService -> bank.*Repository（发布同事务写 Outbox、afterCommit evict 缓存）
  auth.CurrentUser -> practice.PracticeController ->（可选 redis 锁守卫）-> practice.PracticeService
        -> practice.*Repository / WrongQuestionQueryMapper(MyBatis)；跨域题面只读走 bank.BankService
  ```
- 源码索引（MustRemember）：[BankController.java](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) 第 15-81 行（5 端点、record :74-80）；[PracticeController.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 15-76 行（类级 STUDENT :20、5 端点、可选锁守卫 :29-35）

# MustUnderstand

- 模块化单体按领域包隔离，保留本地调用、本地事务和简单调试；只有流量、团队或故障边界证明需要时才拆服务。cloud/ 下 6 服务是独立微服务演示（独立聚合/包名/数据库），与 app 不共享代码，两套体系不要混算
- Controller 不承载判分，Repository 不承载授权，Entity 方法维护自身状态不变量；跨模块事实仍通过 Service 组合
- 能力陈述分四态：默认测试验证（90 个 @Test 通过）、环境门控证据（29 个 *_EVIDENCE 跳过：Redis/Kafka/真 MySQL/读写分离/ShardingSphere）、仅设计未实现、cloud 独立演示；ShardingSphere 依赖仅 test scope
- 源码索引（MustUnderstand）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 19-152 行与 [PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 18-214 行的模块职责边界；[ArchitectureTest.java](../../app/src/test/java/com/allen/questionbank/ArchitectureTest.java) 5 条架构规则
- 关键索引：[README.md](../../README.md)、[bank package](../../app/src/main/java/com/allen/questionbank/bank)、[practice package](../../app/src/main/java/com/allen/questionbank/practice)；官方：[Spring Modulith](https://docs.spring.io/spring-modulith/reference/)
