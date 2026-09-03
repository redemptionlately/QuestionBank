# 必须会背会写

- JDBC 访问链是 `DataSource -> Connection -> PreparedStatement -> ResultSet`；必须使用参数绑定防 SQL 注入，并在 finally/try-with-resources 中释放连接、语句和结果集。
- 连接池复用物理连接；HikariCP 的最大池大小不能脱离数据库最大连接数、请求并发、事务持有时间和线程池配置单独决定。
- MyBatis 将 SQL 映射到参数和结果对象；动态 SQL 必须区分 `#{}` 参数绑定和 `${}` 文本拼接，后者不能接收不可信输入。
- InnoDB 的 MVCC 通过 undo 版本和 Read View 支持一致性读；隔离级别影响脏读、不可重复读和幻读，写冲突仍依赖锁。
- 常见隔离级别：READ COMMITTED 每次一致性读可能看到新提交，REPEATABLE READ 在事务内保持快照，SERIALIZABLE 进一步限制并发；具体锁行为必须结合索引和 SQL 分析。
- N+1 查询是一次主查询加每行关联查询；可通过 join fetch、批量查询、投影或明确分页消除，但 join 过宽会造成重复行和大结果集。
- 外部源码索引（会背会写）：[JDBC API](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/module-summary.html)、[MyBatis SQL Map XML](https://mybatis.org/mybatis-3/sqlmap-xml.html)、[HikariCP configuration](https://github.com/brettwooldridge/HikariCP)、[MySQL transaction isolation](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html)

# 必须理解

- 事务隔离、锁等待、死锁和索引选择是同一条数据库事实链；只说“加索引/提高隔离级别”不能证明问题解决。
- 连接池耗尽可能来自慢 SQL、未关闭连接、长事务或池配置过小；增加池大小可能把压力转移到数据库。
- MyBatis/JDBC 让 SQL 更可控，也增加手写映射、分页、事务和 schema 变更责任；JPA 让领域建模方便，但必须关注 flush、懒加载和 N+1。
- 外部源码索引（必须理解）：[MySQL locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)、[MySQL EXPLAIN](https://dev.mysql.com/doc/refman/8.4/en/explain.html)
