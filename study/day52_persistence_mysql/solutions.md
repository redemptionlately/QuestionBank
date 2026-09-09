# Day52 Persistence & MySQL · 题目与标准解答（Solutions）

> JDBC/连接池/MyBatis/InnoDB，结合本项目 JPA + MySQL 8.4。

## Current

### Q1. 写出 JDBC 资源关闭与参数绑定；MyBatis #{} 与 ${} 对照。
```java
String sql = "SELECT id,total_score FROM practice_session WHERE student_id=? AND status=?";
try (Connection c = dataSource.getConnection();
     PreparedStatement ps = c.prepareStatement(sql)) {     // try-with-resources 逆序关闭
    ps.setLong(1, studentId);                               // 参数绑定，防 SQL 注入
    ps.setString(2, "SUBMITTED");
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) { long id = rs.getLong(1); }
    }
}
```
MyBatis：
```xml
<select id="findSubmitted" resultType="...">
  SELECT * FROM practice_session
  WHERE student_id = #{studentId}          <!-- #{} 预编译参数绑定，安全 -->
  ORDER BY ${orderColumn} DESC             <!-- ${} 直接文本拼接，仅用于白名单列名，绝不接用户输入 -->
</select>
```
`#{}` 生成 `?` 占位符；`${}` 原样拼接，有注入风险，只能用于服务端固定白名单（如排序列名）且需自行校验。

---

### Q2. 给定连接池/线程池/DB 上限，计算安全并发并说明假设。
设：Tomcat 最大线程 200、HikariCP maximumPoolSize=30、MySQL `max_connections=100`、应用部署 2 实例、每请求平均持有连接 20ms。
- 单实例 DB 侧吞吐上限 ≈ poolSize / 持连接时间 = 30 / 0.02 = 1500 QPS（理论值）；
- 两实例共需最多 60 连接 < MySQL 上限 100，安全；若部署 4 实例则需 120 > 100，会连不上，必须下调池大小或提高 DB 上限；
- 假设：连接只在访问 DB 时持有、无长事务、无连接泄漏。连接池不是越大越好：过大增加 DB 上下文切换与内存，过小则请求排队（connectionTimeout 快速失败）。

---

### Q3. 为发布和提交分别选隔离级别、索引与锁策略，解释死锁路径。
| 操作 | 隔离级别 | 索引 | 锁策略 |
|---|---|---|---|
| 发布 publish | 默认 RR（或 RC） | uk_paper_version_no、idx_status_published_at | 按主键/唯一索引锁定版本行，短事务更新状态 |
| 提交 submit | RR | practice_session 主键行锁、uk(session,question) | `SELECT … FOR UPDATE` 悲观锁串行化同一会话 |
- **隔离级别**：RC 每次快照读可看到新提交、锁持有更短；RR 事务内可重复读、用 next-key lock 防幻读。本项目依赖“锁定读取 + 唯一键”，RR 即可；
- **死锁路径示例**：事务 A 先锁 session 行再写 item 行，事务 B 以相反顺序锁 → 循环等待。统一按主键升序访问、缩短事务、固定加锁顺序即可避免；InnoDB 检测到死锁回滚代价小的一方（1213），应用捕获后幂等重试。

---

## External

### E1. 构造 N+1 并用批量查询/投影修复，比较 SQL 数量。
若 PaperVersion 关联 List<QuestionVersion> 且懒加载：查 20 个版本再循环取题目 = 1 + 20 = 21 条 SQL。
- **join fetch / EntityGraph**：1 条 join 取回（注意一对多 join 产生重复父行，需 distinct）；
- **批量查询（IN）**：1 条查版本 + 1 条 `WHERE paper_version_id IN (...)` 查全部题目 = 2 条，内存分组；
- **投影 DTO**：只 select 需要列，1 条且不加载实体，最省。
用 SQL 日志统计修复前后条数验证。

### E2. RC 与 RR 下两次查询结果差异。
- **RC**：事务内第一次读到 v1，期间另一事务提交 v2，第二次读能看到 v2（不可重复读）；
- **RR（InnoDB 默认）**：事务第一次快照读建立 ReadView，之后同样查询始终读到同一快照，看不到他人新提交，直到事务结束；
- 当前读（FOR UPDATE/UPDATE）在两种级别都读最新版本并加锁。

### E3. EXPLAIN 出现全表扫描/回表/filesort 的验证顺序。
按固定顺序排查：① 确认 SQL 语义与返回行数；② EXPLAIN 看 type=ALL（全表扫描）、key=NULL、Extra：`Using filesort`（额外排序）、`Using where`（回表后过滤）；③ 检查是否函数包列/隐式转换/违反最左前缀；④ 单变量加/改索引或改写；⑤ 再跑 EXPLAIN/EXPLAIN ANALYZE 对比 rows 与实际耗时。回表过多可用覆盖索引（把查询列纳入索引，Extra 出现 Using index）消除；filesort 可让排序列接在等值列之后利用索引有序性（如本项目 idx_status_published_at）。
