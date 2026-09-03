# 必须会背会写

- Java 后端项目的可审计事实链由需求、领域模型、接口契约、事务/并发策略、持久化结构、错误契约和观测证据组成
- 性能指标必须绑定请求模型、数据规模、并发度、硬件、软件版本、预热、重复次数和统计分位数；缺少测量条件的数字没有可比性
- 当前实现、已验证行为、尚未实现功能和未来设计分别属于事实、证据、缺口和计划，不能互相替代

# 必须理解

- 项目主链路由 HTTP 请求、Controller、Service、Repository、SQL、事务提交和响应序列化依次组成；每一步都对应输入、输出和失败边界
- 难点的技术模型由不变量、并发冲突、失败回滚、替代方案、观测证据和残余限制组成
- Java 核心、Spring、SQL、并发、测试和项目深度构成普通 Java 后端基础能力；Agent 是建立在这些能力之上的扩展
- 源码索引（会背会写）：[BankController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankController.java) 第 20-51 行、[PracticeController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 21-44 行的 HTTP 主链路
- 源码索引（必须理解）：[BankService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankService.java) 第 31-60 行、[PracticeService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 52-85 行的事务/锁/事实边界
