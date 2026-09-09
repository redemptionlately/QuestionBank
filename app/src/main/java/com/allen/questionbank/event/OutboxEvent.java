package com.allen.questionbank.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Outbox 事件：与业务数据在同一个数据库事务里落库。
 * 事务提交前它只是普通的一行数据；事务提交后才由投递器异步发往 Kafka。
 * 这样"改了库但消息丢了"不可能发生——消息的本体就在库里。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 60)
    private String aggregateType;
    @Column(nullable = false)
    private Long aggregateId;
    @Column(nullable = false, length = 60)
    private String eventType;
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;
    @Column(name = "last_error", length = 500)
    private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    public void markSent() {
        this.status = STATUS_SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = STATUS_FAILED;
        this.retryCount++;
        this.lastError = error != null && error.length() > 480 ? error.substring(0, 480) : error;
    }

    /** 失败后放回 PENDING 由下一轮重试：投递器是"至少一次"，由消费端幂等去重。 */
    public void requeue() {
        this.status = STATUS_PENDING;
    }

    /** 消费方幂等键：aggregateType:aggregateId:eventType。 */
    public String eventKey() {
        return aggregateType + ":" + aggregateId + ":" + eventType;
    }
}
