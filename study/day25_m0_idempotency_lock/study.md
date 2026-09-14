# MustRemember

- Repository 锁定查询的源码是（PracticeSessionRepository:14-16）：
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PracticeSession p where p.id = :id")
  Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
  ```
- 源码索引（MustRemember）：[PracticeSessionRepository.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 11-17 行；[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 56-89 行的幂等提交（key 校验 :58-60、行锁 :61、重放 :63-66）
- InnoDB 的 `SELECT ... FOR UPDATE` 锁住 `practice_session` 行，锁在事务 commit/rollback 时释放；第二事务等待第一事务提交或回滚（真 MySQL 锁等待证据门控 MYSQL_EVIDENCE）
- 首次提交保存 key、总分和 submission_result_json；同 key 直接反序列化已保存结果，不重判分、不增加错题；不同 key 返回 409 STATE_CONFLICT；key 缺失/超长 400 IDEMPOTENCY_KEY_REQUIRED
- 幂等三层：①可选 Redis 锁 RedisGuardedPracticeSubmitter（lock.backend=redis，锁名 practice-submit:{id}、TTL 10s、抢锁失败 409 SUBMIT_IN_PROGRESS）→ ② DB 行锁 → ③ uk_submission_question/uk_wrong_question 唯一约束兜底

# MustUnderstand

- 行锁解决同一会话状态转换，不解决 key TTL、request hash、处理中恢复和跨业务通用幂等；项目内专用幂等表的另两个实例是 consumed_event（消费去重）与 outbox_event 的 uk_outbox_dedup（生产去重）
- 多实例不能依赖 Java `synchronized`；数据库锁、唯一键和事务才是跨进程共享防线；Redis 锁只做外层减压，绕过它时 DB 行锁仍必须保正确（RedisGuardedSubmitIntegrationTest:84）
- 源码索引（MustUnderstand）：[PracticeSession.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 15-19 行 submission key/result/version、35-38 行 submit()；[M0FlowIntegrationTest.java](../../app/src/test/java/com/allen/questionbank/M0FlowIntegrationTest.java) 第 179-213 行 concurrentSubmitWithSameKeyReturnsOneStableResult；[RedisGuardedPracticeSubmitter.java](../../app/src/main/java/com/allen/questionbank/practice/RedisGuardedPracticeSubmitter.java) 第 25-48 行
