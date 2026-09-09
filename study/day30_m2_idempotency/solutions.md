# Day30 M2 Idempotency · 题目与标准解答（Solutions）

> 依据：`study.md`；对照 M0 现有“幂等键寄生在 practice_session”的实现，M2 抽象为跨接口/资源/实例的通用幂等记录。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
对照锚点：M0 的 `PracticeService.submit`（key 存在业务行上、行锁串行）；本主题是其通用化设计——独立 `idempotency_record` 表。

---

### Q2. 画两个实例抢同一 key。
```
实例A、实例B 同时收到相同 (scope,business_key) 请求：
  都尝试 INSERT idempotency_record(PROCESSING)
     ├─ A：INSERT 成功（拿到执行权）→ 执行业务 → UPDATE 为 SUCCEEDED + 存响应
     └─ B：主键/唯一键冲突 → 转为 SELECT 已有记录：
             · PROCESSING → 返回 409/处理中（或短暂等待轮询）
             · SUCCEEDED 且 request_hash 相同 → 直接重放 response_body
             · SUCCEEDED 但 request_hash 不同 → 422/409（同 key 不同请求体）
```
跨实例的互斥由**数据库唯一键**保证，不依赖 JVM 锁。

---

### Q3. 设计摘要冲突。
`request_hash = SHA-256(规范化后的请求体)`。同一 business_key 第二次请求：
- hash 与首次相同 → 认定为同一业务操作的重试，返回缓存响应（幂等重放）；
- hash 不同 → 客户端用同一幂等键发起了**不同请求**，属于编程错误，返回 422 Unprocessable Entity（或 409），不能覆盖首次结果。
这防止“同 key 不同语义”被错误去重。

---

### Q4. 写出幂等记录模型（scope/business_key/request_hash/status/响应/过期）。
| 字段 | 作用 |
|---|---|
| scope | 业务域/接口（如 `practice.submit`），与 key 组成主键，避免跨接口串号 |
| business_key | 幂等键（常含用户 id + 资源 id + 客户端 UUID），绑定用户/操作/资源 |
| request_hash | 请求体摘要，检测同 key 不同请求 |
| status | PROCESSING / SUCCEEDED / FAILED |
| response_code / response_body | 首次完成后的响应，用于重放 |
| lease_until | 处理中租约到期时间，供超时接管 |
| created_at / expires_at | 创建时间与清理时间（TTL 只控制清理） |

key 必须绑定用户与资源：只用裸 UUID 可能把 A 用户的响应重放给 B 用户。

---

### Q5. 写出幂等表 DDL 和唯一键竞争后的两条事务分支。
```sql
CREATE TABLE idempotency_record (
    scope         VARCHAR(100) NOT NULL,
    business_key  VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    response_code INT,
    response_body JSON,
    lease_until   TIMESTAMP(6) NULL,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (scope, business_key)
);
```
**分支一（插入成功 = 执行者）**：
1. INSERT PROCESSING（设置 lease_until）；
2. 在业务事务内写业务事实；
3. 业务提交成功后，把记录更新为 SUCCEEDED 并写 response（顺序见 Q6）。
**分支二（唯一键冲突 = 重复请求）**：捕获 DuplicateKeyException → SELECT 记录 → 按 status/request_hash 决定重放、409 处理中、或 422 摘要冲突。

---

### Q6. 设计 PROCESSING 超时接管与过期清理边界，并定义业务事实与响应的提交顺序。
- **超时接管**：PROCESSING 记录的 `lease_until < now` 说明执行实例可能崩溃；其他实例可用条件 UPDATE（`WHERE status='PROCESSING' AND lease_until<now`）抢占为新的 PROCESSING 并重试，前提是**业务处理器幂等**（重复执行不产生重复副作用）。
- **过期清理**：`expires_at` 只决定何时物理删除记录；保留期必须 ≥ 客户端最大重试窗口（客户端超时、网关重试时间），不能业务还在重试窗口内就把记录清掉。
- **提交顺序（关键一致性）**：推荐“业务事实与幂等记录在**同一数据库事务**内提交”——业务写入 + 记录置 SUCCEEDED 一起 commit，避免“返回成功但记录丢失（重试会重复执行）”或“记录成功但业务回滚（重放了不存在的结果）”。若响应需在 commit 后生成，可 commit 后补写 response，但执行权与业务事实必须同事务落定。

---

## External

### E1. 比较 SETNX 与 MySQL 唯一键。
| 维度 | Redis SET NX EX | MySQL 唯一键 INSERT |
|---|---|---|
| 互斥范围 | 快、单命令原子，依赖 Redis 可用性 | 与业务库同库，天然和业务事务一致 |
| 持久化 | 需 AOF/集群与过期策略，重启可能丢 | 随数据库持久化，强一致 |
| 响应存储 | 适合放缓存（设置 TTL） | 可直接存 JSON，便于审计/长期重放 |
| 适用 | 高频、短窗口、可容忍缓存语义 | 要求与业务事实强一致的幂等 |
二者可组合：DB 唯一键做权威去重，Redis 做热点挡重。

### E2. 设计清理任务。
定时任务分批删除 `expires_at < now` 的 SUCCEEDED/FAILED 记录（小批量 LIMIT，避免大事务锁表）；PROCESSING 记录不按 expires_at 直接删，先走租约接管判定，确认执行方死亡后再标记 FAILED 或重试；清理进度可观测（删除条数、最老记录年龄），并保留足够审计窗口。
