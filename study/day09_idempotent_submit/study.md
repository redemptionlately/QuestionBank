# MustRemember

- 提交入口的幂等代码骨架是：
  ```java
  validateKey(idempotencyKey);
  PracticeSession session = sessions.findByIdForUpdate(sessionId)
      .orElseThrow(() -> notFound("练习不存在"));
  if (session.getStatus() == SUBMITTED) {
      if (!idempotencyKey.equals(session.getSubmissionKey()))
          throw conflict("该练习已经使用其他幂等键提交");
      return readResult(session);
  }
  SubmitResult result = grade(session);
  session.submit(idempotencyKey, result.totalScore(), write(result));
  ```
- `Idempotency-Key` 标识一次业务提交；首次保存 key、总分和结果，同 key 重放直接读取已保存结果
- 同一会话使用不同 key 是语义冲突；空白、缺失、超过 100 字符的 key 是请求错误
- 幂等三层：Redis 锁（app.lock.backend=redis 时由 RedisGuardedPracticeSubmitter 在事务外获取、提交后释放，默认关闭）→ DB SELECT…FOR UPDATE 行锁（始终存在）→ uk_submission_question/submission_key 唯一键兜底
- 源码索引（MustRemember）：[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 56-89 行的 key 校验、锁定读取、重放和首次提交；[PracticeSessionRepository.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 14-16 行的锁定查询；[RedisGuardedPracticeSubmitter.java](../../app/src/main/java/com/allen/questionbank/practice/RedisGuardedPracticeSubmitter.java) 第 37-48 行的可选 Redis 锁包装

# MustUnderstand

- `@Lock(PESSIMISTIC_WRITE)` 的 JPQL 查询映射为数据库锁定读取；第二事务在第一事务提交/回滚前等待
- 数据库行锁跨线程和实例保护同一行，但 M0 没有 request hash、TTL、处理中恢复和通用幂等表
- 源码索引（MustUnderstand）：[PracticeSession.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 15-19 行的幂等字段与 @Version；[PracticeSession.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 35-38 行的提交状态；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 49-62 行的 practice_session 防线、第 72 行 uk_submission_question；并发同 key 只出一份结果由 M0FlowIntegrationTest.concurrentSubmitWithSameKeyReturnsOneStableResult 固化
