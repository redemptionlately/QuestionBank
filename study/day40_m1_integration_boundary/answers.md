# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 画 happy path/失败分支
- 明确未实现不能进简历
- 写出事件的 eventType、eventVersion、aggregateId、occurredAt 和幂等标识。
- 写出事件 envelope JSON，并说明消费者重复、乱序和死信处理。
- 画出 M0 发布出口与 M1 导入候选之间的事务边界。

# External

- 设计事件 schema/version
- 模拟重复乱序
