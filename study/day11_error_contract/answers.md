# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 为 400、401、403、404、409 各写一个请求和预期业务码。
- 未知异常不泄露堆栈、SQL、密码和 token。
- 每个错误响应包含 requestId 和 timestamp。
- `/actuator/health` 返回健康状态。
- 解释 HTTP 状态码与业务码为什么要同时存在。

# External

- 让数据库不可用后请求接口，记录返回状态是否符合依赖故障语义。
- 检查错误日志是否能用 requestId 关联请求而不记录敏感正文。
- 对 JSON、纯文本和空 body 分别测试错误响应格式。

