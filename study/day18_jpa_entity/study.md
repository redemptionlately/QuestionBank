# MustRemember

- managed Entity 由 Persistence Context 跟踪字段；事务内 setter 修改后不必显式 `save`，flush 时脏检查生成 SQL；本项目 `spring.jpa.open-in-view=false`，没有请求级隐式 Session
- Repository 派生查询 `findByStudentIdOrderByCreatedAtDesc`（PracticeSessionRepository:12）由方法名生成条件；JPQL 面向实体属性（如 :14-16 的 findByIdForUpdate），原生 SQL 面向表和数据库方言（本项目仅 OutboxPublisher 用 JdbcTemplate 写原生 INSERT）
- `FetchType.LAZY` 关系只在访问时查询；Persistence Context 关闭后访问未初始化关系可能抛出 `LazyInitializationException`；本项目全实体用标量外键 ID、无对象关联，从源头规避
- JDBC 的安全查询必须使用 `PreparedStatement` 参数绑定；连接、Statement、ResultSet 都要关闭；连接池复用连接但不改变事务提交和回滚语义
- MyBatis Mapper 把 SQL 显式保留在 XML 中，适合「全可选条件+分页+聚合」的复杂只读查询；JPA 管全部写入与等值读——本项目双栈分工：JPA 11 张表实体/Repository，MyBatis 只接管 wrong_question 查询（WrongQuestionQueryMapper + mapper XML，namespace 二级缓存 60s）
- Repository 的源码形态是：
  ```java
  public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {
      List<PracticeSession> findByStudentIdOrderByCreatedAtDesc(Long studentId);

      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("select p from PracticeSession p where p.id = :id")
      Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
  }
  ```
- 源码索引（MustRemember）：[PracticeSession.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 6-39 行（字段/注解 9-19、@Version :19、submit :35-38）；[PracticeSessionRepository.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 11-17 行的派生查询 :12 和悲观锁定查询 :14-16

# MustUnderstand

- N+1 是一次父查询加每个父对象的一次子查询；投影、`join fetch` 和批量查询分别减少列、合并关联或批量加载，适用条件不同；标量 ID + 显式批量查询在结构上消除关联 N+1
- M0 使用标量外键 ID，让 Service 显式检查归属（PracticeService.requireSession :120-124：不存在 404、非本人 403），避免 Entity 关系序列化和级联边界失控
- 乐观锁 @Version（PracticeSession:19）与悲观锁 findByIdForUpdate（Repository:14-16）并存：前者防丢失更新、后者把同一 session 的提交强串行化，最终正确性还有 uk_submission_question 唯一约束兜底
- 源码索引（MustUnderstand）：[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 120-124 行 requireSession 归属检查、第 105-115 行 wrongBook 调 MyBatis；[WrongQuestionQueryMapper.java](../../app/src/main/java/com/allen/questionbank/practice/WrongQuestionQueryMapper.java) 第 17-30 行；[WrongQuestionQueryMapper.xml](../../app/src/main/resources/mapper/WrongQuestionQueryMapper.xml) 全文 42 行
- 外部源码索引（MustRemember）：[JDBC `PreparedStatement`](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html) 的参数绑定与资源关闭；[MyBatis Mapper XML](https://mybatis.org/mybatis-3/sqlmap-xml.html) 的 `select`、参数和结果映射
- 外部源码索引（MustUnderstand）：[HikariCP configuration](https://github.com/brettwooldridge/HikariCP) 的连接池上限、超时和泄漏检测；[Spring Data JPA projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
