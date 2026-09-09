package com.allen.questionbank.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);

    boolean existsByAggregateTypeAndAggregateIdAndEventType(String aggregateType, Long aggregateId, String eventType);
}
