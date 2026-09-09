# Day25 M0 Idempotency & Lock · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `PracticeSessionRepository.findByIdForUpdate`、`PracticeService.submit`、并发测试 178-212 行。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：Repository 11-16 行 `@Lock`/JPQL；`PracticeService` 52-85 行幂等提交；`PracticeSession` 15-19 行 submissionKey、35-38 行 result/status/version；并发测试 178-212 行。

---

### Q2. 运行并发同 key。
对应测试：两个线程/请求用**相同** Idempotency-Key 同时提交同一 session。`findByIdForUpdate` 的 `SELECT ... FOR UPDATE` 让二者在数据库层串行：先到者完成判分并置 SUBMITTED，后到者拿到锁后读到 SUBMITTED + 相同 key，走重放分支返回同一结果。断言：两次响应相等、总分一致、只产生一份提交结果。

---

### Q3. 验证 wrongCount。
首次提交判错的题写入 WrongQuestion（wrongCount=1）；同 key 并发重放不进入判分循环，因此无论重放多少次，`wrongCount` 始终为 1。错题计数只在“首次判分”时变化。

---

### Q4. 不同 key 返回 409。
会话已 SUBMITTED 且请求携带的 key 与已存 `submission_key` 不同 → `STATE_CONFLICT` 409「该练习已经使用其他幂等键提交」。语义：一个业务提交只能由一个幂等键声明，防止两个客户端把同一结果分别认领。

---

### Q5. 写出 SELECT FOR UPDATE、同 key 重放、不同 key 冲突和行锁释放时机。

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)                 // 生成 SELECT ... FOR UPDATE
@Query("select p from PracticeSession p where p.id = :id")
Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
```
执行逻辑：
1. key 校验（缺失/空白/超长 → 400）；
2. `findByIdForUpdate` 对 practice_session 目标行加 **X 行锁**；
3. 校验 studentId；
4. 若 SUBMITTED：key 相同 → 反序列化 `submission_result_json` 返回（不判分、不写错题）；key 不同 → 409；
5. 若 IN_PROGRESS：判分、写 Item/Wrong、`session.submit(...)`。

**行锁释放时机**：InnoDB 行锁在**事务提交或回滚时释放**（不是方法返回、也不是语句结束）。因此整个判分+多表写入都在锁保护内，第二事务在第一事务 commit/rollback 前阻塞等待，锁等待超时由 `innodb_lock_wait_timeout` 决定。

---

## External

### E1. 去掉行锁做对照实验。
把 `@Lock(PESSIMISTIC_WRITE)` 改成普通 `findById`：两个并发同 key 请求可能都读到 IN_PROGRESS，于是都执行判分——Item 被重复 grade、WrongQuestion 出现“查不到就插”的竞争（靠唯一键其中一个报错）、总分/结果被覆盖写。对照证明：行锁把“检查状态 + 写入结果”变成临界区，是幂等正确性的关键，而不是可有可无的优化。

### E2. 设计跨实例通用幂等表。
M0 行锁只解决“同一 session 行的状态转换”，不含 key TTL、request hash、PROCESSING 恢复，且幂等状态寄生在业务行上。通用方案是独立幂等表：
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
流程：首次请求先 INSERT PROCESSING（主键冲突说明重复）→ 处理 → 更新 SUCCEEDED+响应；重复请求：PROCESSING 返回 409/处理中、SUCCEEDED 返回缓存响应、同 key 但 request_hash 不同返回 422；PROCESSING 超时由后台接管。跨实例靠数据库唯一键而不是 JVM `synchronized`（后者只在单进程内有效）。
