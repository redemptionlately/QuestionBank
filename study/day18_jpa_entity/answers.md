# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 观察 save/flush/commit SQL
- 解释标量外键选择
- 分别写出派生查询、JPQL、原生 SQL，并说明 LAZY 关系在事务外访问的结果。
- 写出 Persistence Context 四种状态、脏检查和一个派生查询 Repository。
- 写出 `PreparedStatement` 的参数绑定和 try-with-resources 关闭顺序；比较 JPA 派生查询、MyBatis SQL 和 N+1 的适用边界。
- 写出连接、PreparedStatement、ResultSet 的关闭顺序，并说明连接池复用连接时事务状态如何恢复。

# External

- 制造 N+1 并修复
- 测乐观锁冲突
- 用连接池参数解释连接获取超时、最大连接数和泄漏检测；写一个 MyBatis `select` 映射结果。
