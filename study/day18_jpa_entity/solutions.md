# Day18 JPA Entity · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `PracticeSession`、`PracticeSessionRepository`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PracticeSession` 6-19 行实体字段/注解、19 行 `@Version`、35-38 行状态方法；Repository 11-16 行派生查询与锁定查询；`PracticeService` 102-112 行归属检查。

---

### Q2. 观察 save/flush/commit 时的 SQL。
开启 SQL 日志后观察顺序：
- `save(new Entity)` 只是让实体进入持久化上下文（managed），**不一定立即发 SQL**；
- `flush`（事务提交前或查询触发）时脏检查生成 `INSERT/UPDATE`：自增 id 的新实体通常在 flush 时发 INSERT 并回填 id；
- managed 实体在事务内直接 setter 修改，无需再次 save，flush 时自动 `UPDATE`；
- `commit` 提交事务，数据库才正式落库；回滚则此前 SQL 全部撤销。
口诀：save=纳入管理，flush=同步到库（未提交），commit=提交生效。

---

### Q3. 解释标量外键选择。
本项目实体之间**不建 `@ManyToOne` 对象关联，而是存外键 ID（标量）**，如 `PracticeSession.paperVersionId: Long`。理由：
- 聚合边界清晰：practice 不级联操控 bank 的实体，避免级联保存/删除失控；
- 避免懒加载在事务外序列化实体触发 `LazyInitializationException`；
- 归属校验由 Service 显式查询完成（`requireSession/requireQuestion`），授权逻辑可见、可控；
- 聚合之间通过 ID 引用是 DDD 推荐做法，跨聚合不持有对象引用。

---

### Q4. 分别写出派生查询、JPQL、原生 SQL，并说明 LAZY 事务外访问结果。

```java
// ① 派生查询：方法名即查询语义（findBy + 字段 + OrderBy...）
List<PracticeSession> findByStudentIdOrderByCreatedAtDesc(Long studentId);

// ② JPQL：面向实体和属性（类名/字段名），可跨方言
@Query("select p from PracticeSession p where p.studentId = :id order by p.createdAt desc")
List<PracticeSession> jpql(@Param("id") Long id);

// ③ 原生 SQL：面向表和列，数据库方言相关
@Query(value = "select * from practice_session where student_id = :id order by created_at desc",
       nativeQuery = true)
List<PracticeSession> nativeSql(@Param("id") Long id);
```
`FetchType.LAZY`：关联只在**会话打开且首次访问 getter** 时才发 SQL 加载；持久化上下文关闭（事务结束、实体脱离会话 detached）后再访问未初始化关联 → 抛 `org.hibernate.LazyInitializationException`。本项目用标量 ID 从源头规避。

---

### Q5. 写出持久化上下文四种状态、脏检查和一个派生查询 Repository。

**四种实体状态**：
| 状态 | 含义 | 典型操作 |
|---|---|---|
| transient（瞬时/新建） | 刚 new，未被持久化上下文管理，无 id | `new PracticeSession(...)` |
| managed（托管） | 已纳入持久化上下文，变更会被脏检查跟踪 | `save` 返回对象、查询结果 |
| detached（游离） | 曾被管理但会话已关闭，有 id，改了不会自动同步 | 事务结束后仍持有的对象 |
| removed（删除） | 标记删除，flush 时发 DELETE | `delete(entity)` |

**脏检查（dirty checking）**：flush 时 Hibernate 对比 managed 实体当前值与加载时快照，有差异才生成 UPDATE。
**派生 Repository**：
```java
public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {
    List<PracticeSession> findByStudentIdOrderByCreatedAtDesc(Long studentId);
}
```

---

### Q6. 写出 PreparedStatement 参数绑定和 try-with-resources 关闭顺序；比较 JPA 派生/MyBatis/N+1。

```java
// 参数绑定：用 ? 占位 + setXxx，杜绝 SQL 注入（绝不拼接字符串）
String sql = "select id, total_score from practice_session where student_id = ? order by created_at desc";
try (Connection conn = dataSource.getConnection();       // 关闭顺序与声明相反：ResultSet → Statement → Connection
     PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setLong(1, studentId);                            // 类型安全绑定
    try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) { long id = rs.getLong("id"); }
    }
}
```
try-with-resources 按**逆序自动关闭**，即使抛异常也关闭。
**适用边界**：
- JPA 派生查询：简单条件 CRUD，零 SQL 样板；方法名过长/隐式 join 时可读性下降；
- MyBatis：复杂多表、动态 SQL、需要精细控制执行计划时更合适，SQL 显式写在 XML/注解；
- **N+1**：一次查 N 个父对象，再为每个父对象发一次子查询（共 1+N 次）。解法：投影只取需要的列、`join fetch`/`@EntityGraph` 一次 join 取回、批量加载（`IN` 分批）。

---

### Q7. 连接/Statement/ResultSet 关闭顺序，连接池复用时事务状态如何恢复。
- 关闭顺序：先 `ResultSet`，再 `Statement/PreparedStatement`，最后 `Connection`（try-with-resources 自动逆序处理）；
- 连接池（HikariCP）中 `conn.close()` **不是物理关闭，而是归还连接池**；归还前连接会被重置：自动提交恢复为默认、未提交事务被回滚、Statement 被清理，因此下一个借到该连接的请求拿到的是干净连接，不会继承上一个请求的事务/隔离级别设置。

---

## External

### E1. 制造 N+1 并修复。
制造：查出 100 个 PaperVersion，循环里访问每个的 `questions`（若用对象关联且 LAZY），会发 1 次 + 100 次查询。修复：① `@Query("... left join fetch p.questions ...")` 一次取回；② 或分两条查询（先查 id 列表，再 `where paper_version_id in (...)` 批量取题目）在内存分组；③ 用投影 DTO 只选需要列。修复后用 SQL 日志确认查询次数从 N+1 降为常数次。

### E2. 测乐观锁冲突。
`PracticeSession.entityVersion` 标注 `@Version`：两个事务先后读到同一 version=1，A 先提交（version→2），B 提交时 `WHERE id=? AND entity_version=1` 匹配不到 → 抛 `ObjectOptimisticLockingFailureException`。测试断言该异常，并决定重试或转成 409。乐观锁适合冲突少的场景；本项目提交路径用悲观行锁 `findByIdForUpdate` 强串行化。

### E3. 用连接池参数解释获取超时、最大连接数、泄漏检测；写一个 MyBatis select 映射。
HikariCP 关键参数：
- `maximumPoolSize`：最大物理连接数，决定并发数据库操作上限；
- `connectionTimeout`：池满时获取连接最多等待多久，超时抛 `SQLTransientConnectionException`（快速失败而非无限挂起）；
- `leakDetectionThreshold`：连接借出超过该阈值未归还则打泄漏告警，定位“忘了 close/长事务占连接”。

MyBatis select 映射：
```xml
<select id="findByStudent" resultType="PracticeSessionView">
  SELECT id, total_score AS totalScore, created_at AS createdAt
  FROM practice_session
  WHERE student_id = #{studentId}
  ORDER BY created_at DESC
</select>
```
`#{studentId}` 是预编译参数绑定（防注入），区别于字符串替换的 `${}`。
