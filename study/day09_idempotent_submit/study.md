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
- 源码索引（MustRemember）：[PracticeService.java](../../src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 52-85 行的 key 校验、锁定读取、重放和首次提交；[PracticeSessionRepository.java](../../src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 14-16 行的锁定查询

# MustUnderstand

- `@Lock(PESSIMISTIC_WRITE)` 的 JPQL 查询映射为数据库锁定读取；第二事务在第一事务提交/回滚前等待
- 数据库行锁跨线程和实例保护同一行，但 M0 没有 request hash、TTL、处理中恢复和通用幂等表
- 源码索引（MustUnderstand）：[PracticeSession.java](../../src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 15-19 行的幂等字段；[PracticeSession.java](../../src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第35-38行的提交状态；[V1__m0_schema.sql](../../src/main/resources/db/migration/V1__m0_schema.sql) 第 49-62 行的数据库防线
