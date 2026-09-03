# 必须会背会写的验收
- 打开并按当天 study.md 的“源码索引（会背会写）”或“外部源码索引（会背会写）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 复现缺 token/无效 token/学生访问 admin
- 写出 `SecurityFilterChain` 的 `permitAll`、`authenticated` 和角色授权规则，并说明过滤器顺序。
- 写出 BCrypt 编码、`matches` 校验和 Bearer 过滤器处理缺失/错误/未知 token 的分支。

# 额外测试与追问

- 设计过期/撤销/刷新
- 检查日志不泄露 token
