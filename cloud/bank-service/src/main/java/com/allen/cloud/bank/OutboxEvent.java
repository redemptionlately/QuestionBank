package com.allen.cloud.bank;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 事务性发件箱（Transactional Outbox）。
 *
 * <p>问题：发布试卷既要写库又要发 Kafka，两步不是一个事务，
 * "库写了消息没发"和"消息发了事务回滚"都会造成不一致。
 *
 * <p>解法：把消息当成一行数据，和业务数据在<b>同一个本地事务</b>里写入本表，
 * 再由独立的 relay 轮询投递。这样"业务成功"与"消息待发"是原子的，
 * 剩下的只有"至少一次投递"，由消费端幂等兜底。
 *
 * <p>event_key 是幂等键，消费端按它去重。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 120)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 2000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean sent;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventKey, String eventType, String payload) {
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.sent = false;
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void markSent() {
        this.sent = true;
    }
}
