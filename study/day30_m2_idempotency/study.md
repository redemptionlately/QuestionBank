# 必须会背会写

- 通用幂等表字段是 `scope`、`business_key`、`request_hash`、`status`、`response_code`、`response_body`、`created_at`、`expires_at`、`lease_until`
- 唯一键竞争模式是 `INSERT`；插入成功者执行，唯一键冲突者读取已有记录；相同摘要返回已有响应，不同摘要返回 409
- `PROCESSING/SUCCEEDED/FAILED` 状态区分处理中、已完成和可重试失败；处理中记录依靠 lease/超时接管
- 幂等记录必须在业务事实和响应之间定义提交顺序，避免返回成功但记录丢失或记录成功但业务回滚
- 表结构的核心约束是：
  ```sql
  CREATE TABLE idempotency_record (
      scope VARCHAR(100), business_key VARCHAR(128), request_hash CHAR(64),
      status VARCHAR(20), response_body JSON, lease_until TIMESTAMP NULL,
      PRIMARY KEY (scope, business_key)
  );
  ```
- 外部源码索引（会背会写）：[Stripe idempotency_key](https://docs.stripe.com/api/idempotent_requests) 的请求键与响应重放契约；[MySQL UNIQUE](https://dev.mysql.com/doc/refman/8.4/en/constraint-primary-key.html) 的唯一键冲突语义

# 必须理解

- M0 的 key 存在 `practice_session`，M2 才抽象跨接口、资源和实例的幂等记录
- key 必须绑定用户、操作、资源和请求语义；只用裸 key 可能把其他用户的响应读回
- TTL 只控制清理，不代表业务响应可以安全遗忘；重试窗口、客户端超时和保留期要一致
- 外部源码索引（必须理解）：[Redis SET NX](https://redis.io/docs/latest/commands/set/) 的租约锁边界；[MySQL transactions](https://dev.mysql.com/doc/refman/8.4/en/innodb-transactional-model.html) 的提交/回滚事实
- 官方：[Stripe Idempotency](https://docs.stripe.com/api/idempotent_requests)
