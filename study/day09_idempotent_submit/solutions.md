# Day09 Idempotent Submit · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `PracticeService.submit`、`PracticeSessionRepository.findByIdForUpdate`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（主代码在 `app/src/main/java/com/allen/questionbank/`）：[practice/PracticeService.java:56-89](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) submit 的 key 校验（:58-60）/FOR UPDATE 锁定读取（:61）/重放（:63-66）/首次判分提交（:67-88）；[practice/PracticeSessionRepository.java:14-16](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 悲观锁查询；[practice/PracticeSession.java:15-16](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 幂等字段、:19 `@Version`、:35-38 submit()；[practice/RedisGuardedPracticeSubmitter.java:37-48](../../app/src/main/java/com/allen/questionbank/practice/RedisGuardedPracticeSubmitter.java) 可选 Redis 锁协调器（app.lock.backend=redis 时装配）；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 49-62 行 practice_session、第 72 行 uk_submission_question 数据库防线。

---

### Q2. 写出第一次提交、相同 key 重试、不同 key 重试三条分支。

**真实代码骨架**：
```java
@Transactional
public SubmitResult submit(AuthPrincipal user, Long sessionId, String idempotencyKey) {
    // 分支 0：key 合法性（缺失/空白/超长 → 400）
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100)
        throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "提交必须携带 Idempotency-Key");

    // 行级悲观锁读取：并发请求在此串行化
    PracticeSession session = sessions.findByIdForUpdate(sessionId)
        .orElseThrow(() -> notFound("练习不存在"));
    if (!session.getStudentId().equals(user.userId())) throw forbidden();

    if (session.getStatus() == PracticeStatus.SUBMITTED) {
        // 分支 2/3：终态会话只允许“同 key 重放”，不同 key 是冲突
        if (!idempotencyKey.equals(session.getSubmissionKey()))
            throw conflict("该练习已经使用其他幂等键提交");
        return readResult(session);                       // 分支 2：同 key，返回已存结果
    }
    // 分支 1：首次提交，判分、聚合错题、落总分与结果 JSON（PracticeService.java:67-88）
    SubmitResult result = grade(session);                // :67-85 判分循环
    session.submit(idempotencyKey, result.totalScore(), write(result));
    sessions.save(session);
    return result;
}
```
锁定查询：
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)                   // SELECT ... FOR UPDATE
@Query("select p from PracticeSession p where p.id = :id")
Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
```
三分支总结：
1. **首次提交（IN_PROGRESS）**：判分 → 写 `status=SUBMITTED、submission_key、total_score、submission_result_json`；
2. **同 key 重试（SUBMITTED + key 相同）**：直接反序列化返回已存结果，**不再判分、不再写错题**；
3. **不同 key 重试（SUBMITTED + key 不同）**：409 `STATE_CONFLICT`。

**幂等是三层结构，不是只有行锁**：① 可选的 Redis 分布式锁——`PracticeController.submit`（:46-53）在 `app.lock.backend=redis` 时先走 `RedisGuardedPracticeSubmitter.submit`（:37-48），按 `practice-submit:{sessionId}` 加 10s TTL 锁，抢锁失败返回 409 `SUBMIT_IN_PROGRESS`，锁在事务外获取、finally 中事务提交后释放；② DB 行锁 `findByIdForUpdate`（默认形态，始终存在）；③ 唯一键 `uk_submission_question`/`submission_key` 做最终正确性兜底。Redis 锁只负责减少冲突与数据库压力，关掉开关（默认）时②③仍保证正确。

---

### Q3. 缺少 `Idempotency-Key` 返回 400。
Controller 用 `@RequestHeader("Idempotency-Key") String key` 接收；缺失时 Spring 抛 `MissingRequestHeaderException` → 全局处理器映射 400 `REQUEST_INVALID`；key 空白/超长在 Service 显式抛 400 `IDEMPOTENCY_KEY_REQUIRED`。

---

### Q4. 相同 key 重试返回相同总分和逐题结果。
首次提交把 `SubmitResult(sessionId,totalScore,maxScore,List<GradedAnswer>)` 序列化为 `submission_result_json` 落库；重放走 `readResult` 直接反序列化返回，因此总分、每题对错与首次**字节级一致**（集成测试断言两次响应字符串相等）。

---

### Q5. 相同 key 重试不重复增加错题次数。
错题聚合（`wrongQuestions...ifPresentOrElse(markWrong, save new)`）只在**首次判分循环**中执行；同 key 重放提前 `return readResult(session)`，根本不进入判分循环，`wrongCount` 不会二次增长。

---

### Q6. 不同 key 重试返回 409，提交后保存答案返回 409。
- 已 SUBMITTED 却换 key：`STATE_CONFLICT` 409（防止同一业务结果被两个客户端分别声明）；
- 已提交后再 `PUT answers`：`saveAnswer` 判断 `status != IN_PROGRESS` → 409「练习已提交，答案不可修改」。终态不可变。

---

## External

### E1. 同一会话并发两个相同 key 请求，最终只有一个结果。
默认形态下两事务都执行 `findByIdForUpdate`（`SELECT ... FOR UPDATE`）：第一个拿到行锁完成提交，第二个在数据库层阻塞等待；待第一个提交后第二个读到 SUBMITTED + 相同 key，走重放分支返回同一结果。跨线程、跨实例都由数据库行锁保护。该行为由 `M0FlowIntegrationTest.concurrentSubmitWithSameKeyReturnsOneStableResult`（:179）固化；开启 `app.lock.backend=redis` 后，冲突在进入事务前就被 Redis 锁串行化，由 `RedisGuardedSubmitIntegrationTest.redisLockSerializesConcurrentSubmitsOnTheSameSession`（:45）与 `databaseRowLockStillGuardsCorrectnessWhenRedisLockIsBypassed`（:84，证明绕过 Redis 锁时 DB 行锁仍保正确）覆盖。

### E2. 同一会话并发两个不同 key 请求，记录谁成功谁冲突。
先拿到锁的请求以 key-A 完成提交；后拿到锁的请求读到 SUBMITTED，发现自己的 key-B 与已存 key-A 不同 → 409。结果确定：一个 200、一个 409，不会产生两份结果。

### E3. 解释去掉同步锁后哪些数据库写入可能重复。
没有 `FOR UPDATE` 行锁时，两个并发事务可能同时读到 IN_PROGRESS，于是：① 判分循环各执行一次，`submission_item` 被重复 `grade/save`（uk_submission_question 会让其中一次写入失败）；② `wrong_question` 的“查不到就 insert”两次都查不到 → 插入两条（uk_wrong_question 兜底时其中一条报错）；③ 总分/结果被覆盖写。行锁把“检查状态 + 写入”变成临界区，是幂等正确的关键；Redis 锁（RedisGuardedPracticeSubmitter）只是在更前面挡掉冲突、减轻数据库锁等待，不是正确性的唯一依托。注意 app 单体是“Redis 锁（可选）+ 数据库行锁 + 唯一键”的幂等，不是通用幂等表方案（无 request hash、TTL、PROCESSING 恢复）。
