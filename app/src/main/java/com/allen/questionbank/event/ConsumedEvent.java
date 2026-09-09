package com.allen.questionbank.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 已消费事件登记表：消费幂等的核心。
 * Kafka 至少一次投递，同一 eventKey 可能到达多次；唯一键 uk_consumed_event_key 让重复插入冲突，
 * 消费端据此跳过。eventKey 由投递信封携带，与业务 payload 解耦。
 * 注意：必须是顶层类——Spring Data JPA 的仓储扫描不识别嵌套接口。
 */
@Entity
@Table(name = "consumed_event")
public class ConsumedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_key", nullable = false, length = 190)
    private String eventKey;
    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt = Instant.now();

    protected ConsumedEvent() {}

    public ConsumedEvent(String eventKey) { this.eventKey = eventKey; }

    public Long getId() { return id; }
    public String getEventKey() { return eventKey; }
}
