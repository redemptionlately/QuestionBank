# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 从零启动保存 HTTP E2E
- 检查迁移和幂等
- 写出健康探针、应用日志、Flyway 历史、业务响应各自能够证明和不能证明的事实。
- 写出 `set -o pipefail | tee` 的证据命令，并列出不得写入日志的字段。
- 为一个 Service 规则分别设计 Mockito 单元测试、Spring 集成测试和 Testcontainers 数据库测试，并说明三者不能证明的事实。
- 写出测试金字塔中单元、切片、集成、契约测试的速度、隔离性和真实性差异。

# External

- 停止 MySQL 保存失败日志
- 设计 requestId/指标
- 为同一业务规则分别写 mock 交互断言、真实数据库断言和契约字段断言。
