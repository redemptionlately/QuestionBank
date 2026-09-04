# MustRemember

- Auth、Bank、Practice 三个模块分别围绕用户认证、题库版本、练习聚合；调用方向是 `Controller -> Service -> Repository -> MySQL`
- 每个请求都同时受到身份边界、资源边界、状态边界和数据边界约束；通过角色不代表拥有具体 bank/session
- MySQL 是正式事实来源，HTTP 响应是 DTO 投影，`submission_result_json` 保存提交结果快照用于幂等重放
- M0 模块依赖骨架是：
  ```text
  auth.CurrentUser -> bank.BankController -> bank.BankService -> bank.*Repository
  auth.CurrentUser -> practice.PracticeController -> practice.PracticeService -> practice.*Repository
  ```
- 源码索引（MustRemember）：[BankController.java](../../src/main/java/com/allen/questionbank/bank/BankController.java) 第13-52行和 [PracticeController.java](../../src/main/java/com/allen/questionbank/practice/PracticeController.java) 第13-45行的接口入口与 DTO 投影

# MustUnderstand

- 模块化单体按领域包隔离，保留本地调用、本地事务和简单调试；只有流量、团队或故障边界证明需要时才拆服务
- Controller 不承载判分，Repository 不承载授权，Entity 方法维护自身状态不变量；跨模块事实仍通过 Service 组合
- 源码索引（MustUnderstand）：[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第29-106行与 [PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第33-160行的模块职责边界
- 关键索引：[README.md](../../README.md)、[bank package](../../src/main/java/com/allen/questionbank/bank)、[practice package](../../src/main/java/com/allen/questionbank/practice)；官方：[Spring Modulith](https://docs.spring.io/spring-modulith/reference/)
