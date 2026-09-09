-- Outbox 模式：业务数据与待发事件在同一个数据库事务里落库，
-- 事务提交后再由投递器异步发送到 Kafka，避免"库里改了但消息没发出去"。
-- uk_outbox_dedup 保证同一聚合上的同一事件不会重复入队（配合状态机推进）。

CREATE TABLE outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP NULL,
    CONSTRAINT uk_outbox_dedup UNIQUE (aggregate_type, aggregate_id, event_type)
);

CREATE INDEX idx_outbox_pending ON outbox_event (status, created_at);

-- 消费端幂等：Kafka 是至少一次投递，重复消息必须靠唯一键挡住。
CREATE TABLE consumed_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(190) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_consumed_event_key UNIQUE (event_key)
);
