# Day25 M0 Idempotency & Lock · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `PracticeSessionRepository.findByIdForUpdate`（:14-16）、`PracticeService.submit`（:56-89）、并发测试 M0FlowIntegrationTest.concurrentSubmitWithSameKeyReturnsOneStableResult（:179-213）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[PracticeSessionRepository.java:11-17](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java)（@Lock/JPQL 在 :14-16）；[PracticeService.java:56-89](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 幂等提交（key 校验 :58-60、行锁 :61、归属 :62、重放 :63-66、判分循环 :70-84）；[PracticeSession.java:15-19](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) submissionKey/resultJson/@Version、:35-38 submit()；并发测试 :179-213（两线程 CountDownLatch 同时提交、断言两响应体相等 :209）。

---

### Q2. 运行并发同 key。
对应测试 `concurrentSubmitWithSameKeyReturnsOneStableResult`（:179，默认运行）：固定线程池 2 + CountDownLatch 让两个请求用**相同** Idempotency-Key 同时提交同一 session（:199-208）。`findByIdForUpdate` 的 `SELECT ... FOR UPDATE` 让二者在数据库层串行：先到者完成判分并置 SUBMITTED，后到者拿到锁后读到 SUBMITTED + 相同 key，走重放分支（:63-65）反序列化 submission_result_json 返回同一结果。断言：两次响应字符串相等（:209）、只产生一份提交结果。

---

### Q3. 验证 wrongCount。
首次提交判错的题写入 WrongQuestion（wrongCount=1，PracticeService:77-81）；同 key 重放在 :63-65 提前 return，**不进入判分循环**，因此无论重放多少次，`wrong_count` 始终为 1。错题计数只在“首次判分”时变化，另有 uk_wrong_question(student_id,question_version_id) 做最终兜底。

---

### Q4. 不同 key 返回 409。
会话已 SUBMITTED 且请求携带的 key 与已存 `submission_key` 不同 → PracticeService:64 抛 409 `STATE_CONFLICT`「该练习已经使用其他幂等键提交」。语义：一个业务提交只能由一个幂等键声明，防止两个客户端把同一结果分别认领。key 本身缺失/空白/长度>100 在 :58-60 先抛 400 `IDEMPOTENCY_KEY_REQUIRED`。

---

### Q5. 写出 SELECT FOR UPDATE、同 key 重放、不同 key 冲突和行锁释放时机。

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)                 // 生成 SELECT ... FOR UPDATE
@Query("select p from PracticeSession p where p.id = :id")
Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);   // PracticeSessionRepository:14-16
```
执行逻辑（PracticeService.submit :56-89）：
1. key 校验（缺失/空白/超长 → 400 IDEMPOTENCY_KEY_REQUIRED，:58-60）；
2. `findByIdForUpdate` 对 practice_session 目标行加 **X 行锁**（:61），不存在 404；
3. 校验 studentId 归属（:62，不匹配 403）；
4. 若 SUBMITTED（:63）：key 相同 → readResult 反序列化 `submission_result_json` 返回（:65，不判分、不写错题）；key 不同 → 409（:64）；
5. 若 IN_PROGRESS：拉题目、判分、写 Item/Wrong（:67-84）、`session.submit(...)` 汇总落库（:86-87）。

**行锁释放时机**：InnoDB 行锁在**事务提交或回滚时释放**（不是方法返回、也不是语句结束）。因此整个判分+多表写入都在锁保护内，第二事务在第一事务 commit/rollback 前阻塞等待，锁等待超时由 `innodb_lock_wait_timeout` 决定，真 MySQL 证据见 MysqlRealDbIntegrationTest.forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout（:227，MYSQL_EVIDENCE 门控）。

**本项目幂等/并发是三层结构**（重构后新增最外层）：①可选 Redis 分布式锁 RedisGuardedPracticeSubmitter（app.lock.backend=redis 时装配，锁名 `practice-submit:{sessionId}`、TTL 10s，抢锁失败 409 SUBMIT_IN_PROGRESS，锁在事务外获取、提交后释放）→ ②DB 行锁 findByIdForUpdate → ③唯一约束 uk_submission_question / submission_key 最终兜底。Redis 锁减少冲突、DB 锁与唯一键保证正确，RedisGuardedSubmitIntegrationTest 两个门控用例（:45 锁串行化、:84 绕过 Redis 锁时 DB 行锁仍保正确）固化这一分工。

---

## External

### E1. 去掉行锁做对照实验。
把 `@Lock(PESSIMISTIC_WRITE)` 改成普通 `findById`：两个并发同 key 请求可能都读到 IN_PROGRESS，于是都执行判分——Item 被重复 grade、WrongQuestion 出现“查不到就插”的竞争（靠 uk_wrong_question/uk_submission_question 唯一键让其中一个报错）、总分/结果被覆盖写。对照证明：行锁把“检查状态 + 写入结果”变成临界区，是幂等正确性的关键，而不是可有可无的优化。项目里的反向证据是 RedisGuardedSubmitIntegrationTest.databaseRowLockStillGuardsCorrectnessWhenRedisLockIsBypassed（:84）：故意绕过 Redis 锁，证明 DB 行锁+唯一键仍能独立保正确——任何一层缓存/协调组件缺失都不允许产生错误结果。

### E2. 设计跨实例通用幂等表。
M0 行锁只解决“同一 session 行的状态转换”，不含 key TTL、request hash、PROCESSING 恢复，且幂等状态寄生在业务行上。项目里已有两个“专用幂等表”实例可对照：V4 的 `consumed_event(event_key UNIQUE)` 是 Kafka 至少一次投递下的消费端幂等表（PaperPublishedEventConsumer 先 existsByEventKey 再处理），`outbox_event` 的 uk_outbox_dedup 是生产端去重。通用方案是独立幂等表：
```sql
CREATE TABLE idempotency_record (
  idempotency_key VARCHAR(100) NOT NULL,
  user_id         BIGINT       NOT NULL,
  request_hash    CHAR(64)     NOT NULL,   -- 请求体摘要，检测同 key 不同请求
  status          VARCHAR(20)  NOT NULL,   -- PROCESSING / SUCCEEDED / FAILED
  response_json   TEXT,
  created_at      DATETIME(6)  NOT NULL,
  expires_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (idempotency_key, user_id)
);
```
流程：首次请求先 INSERT PROCESSING（主键冲突说明重复）→ 处理 → 更新 SUCCEEDED+响应；重复请求：PROCESSING 返回 409/处理中、SUCCEEDED 返回缓存响应、同 key 但 request_hash 不同返回 422；PROCESSING 超时由后台接管（对照 ImportJobRetrySweeper 的退避清扫思路）。跨实例靠数据库唯一键而不是 JVM `synchronized`（后者只在单进程内有效）；Redis 锁（SET NX PX + Lua 比对 token 释放 + fencing token）可做外层减压，但不能替代唯一键。
