# 上半部分：当天会背会写验收
- 打开并按当天 study.md 的“源码索引（会背会写）”或“外部源码索引（会背会写）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 写出 `@Entity`、`@Table`、`@Id`、`@GeneratedValue`、`@Column`。
- 解释主键、外键、唯一约束、状态字段和 `@Version`。
- 在 H2 测试库执行 Flyway V1，并让 `ddl-auto=validate` 通过。
- 验证重复用户名、重复题号、重复会话题目被数据库拒绝。
- 写出 `Path/Files` 的文本读写和 try-with-resources 字节流示例，并说明 JSON 字段兼容、编码和不可信反序列化风险。

# 下半部分：额外测试

- 修改已执行迁移文件后启动，记录 Flyway checksum 错误。
- 删除外键后运行跨用户测试，区分应用防线与数据库防线。
- 使用 MySQL Compose 启动后再次执行迁移，确认不会重复创建表。
