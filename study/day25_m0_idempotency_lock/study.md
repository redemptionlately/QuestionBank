# 必须会背会写

- Repository 锁定查询的源码是：
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PracticeSession p where p.id = :id")
  Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
  ```
- 源码索引（会背会写）：[PracticeSessionRepository.java](../../src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 11-16 行的 `@Lock`/JPQL；[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 53-84 行的幂等提交代码
- InnoDB 的 `SELECT ... FOR UPDATE` 锁住 `practice_session` 行；第二事务等待第一事务提交或回滚
- 首次提交保存 key、总分和 JSON；同 key 直接反序列化已保存结果，不重判分、不增加错题；不同 key 返回 409

# 必须理解

- 行锁解决同一会话状态转换，不解决 key TTL、request hash、处理中恢复和跨业务通用幂等
- 多实例不能依赖 Java `synchronized`；数据库锁、唯一键和事务才是跨进程共享防线
- 源码索引（必须理解）：[PracticeSession.java](../../src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 15-19、35-39 行的 key/result/status/version；[M0FlowIntegrationTest.java](../../src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 176-210 行的同 key 并发证据
