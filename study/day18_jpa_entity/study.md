# 必须会背会写

- managed Entity 由 Persistence Context 跟踪字段；事务内 setter 修改后不必显式 `save`，flush 时脏检查生成 SQL
- Repository 派生查询 `findByStudentIdOrderByCreatedAtDesc` 由方法名生成条件；JPQL 面向实体属性，原生 SQL 面向表和数据库方言
- `FetchType.LAZY` 关系只在访问时查询；Persistence Context 关闭后访问未初始化关系可能抛出 `LazyInitializationException`
- JDBC 的安全查询必须使用 `PreparedStatement` 参数绑定；连接、Statement、ResultSet 都要关闭；连接池复用连接但不改变事务提交和回滚语义
- MyBatis Mapper 把 SQL 显式保留在 XML 或注解中，适合复杂 SQL；JPA 派生查询减少样板但要警惕方法名复杂度、隐式 join 和 N+1
- Repository 的源码形态是：
  ```java
  public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {
      List<PracticeSession> findByStudentIdOrderByCreatedAtDesc(Long studentId);
  }
  ```
- 源码索引（会背会写）：[PracticeSession.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 6-19 行的 Entity 字段/注解；[PracticeSessionRepository.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 11-16 行的派生查询和锁定查询

# 必须理解

- N+1 是一次父查询加每个父对象的一次子查询；投影、`join fetch` 和批量查询分别减少列、合并关联或批量加载，适用条件不同
- M0 使用标量外键 ID，让 Service 显式检查归属，避免 Entity 关系序列化和级联边界失控
- 源码索引（必须理解）：[PracticeService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 102-112 行的 session/question 归属检查；[PracticeSession.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 19 行的 `@Version`
- 外部源码索引（会背会写）：[JDBC `PreparedStatement`](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html) 的参数绑定与资源关闭；[MyBatis Mapper XML](https://mybatis.org/mybatis-3/sqlmap-xml.html) 的 `select`、参数和结果映射
- 外部源码索引（必须理解）：[HikariCP configuration](https://github.com/brettwooldridge/HikariCP) 的连接池上限、超时和泄漏检测；[Spring Data JPA projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
