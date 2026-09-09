package com.allen.questionbank;

import com.allen.questionbank.event.ConsumedEventRepository;
import com.allen.questionbank.event.OutboxEvent;
import com.allen.questionbank.event.OutboxEventRepository;
import com.allen.questionbank.event.OutboxRelay;
import com.allen.questionbank.event.PaperPublishedEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 投递器与消费者的单元测试：mock Kafka，验证状态推进与消费幂等分支。 */
class OutboxRelayAndConsumerTest {

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockKafka(boolean succeed) {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(
                succeed ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new RuntimeException("broker unreachable")));
        return template;
    }

    private OutboxEvent pendingEvent() {
        return new OutboxEvent("paper", 7L, "PAPER_PUBLISHED", "{\"paperId\":7}");
    }

    @Test
    void relayMarksEventSentAfterSuccessfulDelivery() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = pendingEvent();
        when(repository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING)).thenReturn(List.of(event));
        KafkaTemplate<String, String> kafka = mockKafka(true);
        OutboxRelay relay = new OutboxRelay(repository, kafka, new ObjectMapper(), "qb-events", 3);

        relay.relayPendingEvents();

        assertEquals(OutboxEvent.STATUS_SENT, event.getStatus(), "发送成功必须标记 SENT");
        assertTrue(event.getSentAt() != null);
        org.mockito.ArgumentCaptor<String> envelopeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("qb-events"), eq("paper:7:PAPER_PUBLISHED"), envelopeCaptor.capture());
        // 信封内容必须完整（变异测试发现 anyString() 会放过空信封，改为断言实际内容）
        String envelope = envelopeCaptor.getValue();
        assertTrue(envelope.contains("\"eventKey\":\"paper:7:PAPER_PUBLISHED\""), "信封必须带 eventKey: " + envelope);
        assertTrue(envelope.contains("\"eventType\":\"PAPER_PUBLISHED\""), "信封必须带 eventType: " + envelope);
        assertTrue(envelope.contains("\"paperId\":7"), "信封必须带业务 payload: " + envelope);
    }

    @Test
    void relayRequeuesFailedEventUntilMaxAttempts() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = pendingEvent();
        when(repository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING)).thenReturn(List.of(event));
        OutboxRelay relay = new OutboxRelay(repository, mockKafka(false), new ObjectMapper(), "qb-events", 3);

        relay.relayPendingEvents();
        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus(), "未达最大重试次数应回到 PENDING");
        assertEquals(1, event.getRetryCount());
        assertFalse(event.getLastError() == null || event.getLastError().isBlank(), "失败原因要落库");

        // 用实体自身的状态机模拟"又失败了一次后回到 PENDING"，避免暴露测试专用 setter
        event.markFailed("broker unreachable #2");
        event.requeue();
        relay.relayPendingEvents();
        assertEquals(OutboxEvent.STATUS_FAILED, event.getStatus(), "超过最大重试次数必须停在 FAILED 等人工介入");
        assertEquals(3, event.getRetryCount());
    }

    @Test
    void consumerRecordsEventOnceThenSkipsDuplicates() {
        ConsumedEventRepository consumed =
                mock(ConsumedEventRepository.class);
        PaperPublishedEventConsumer consumer = new PaperPublishedEventConsumer(consumed, new ObjectMapper());

        String envelope = "{\"eventKey\":\"paper:7:PAPER_PUBLISHED\",\"eventType\":\"PAPER_PUBLISHED\",\"payload\":{\"paperId\":7}}";
        when(consumed.existsByEventKey("paper:7:PAPER_PUBLISHED")).thenReturn(false);

        consumer.onEvent(envelope);
        verify(consumed).save(any());

        when(consumed.existsByEventKey("paper:7:PAPER_PUBLISHED")).thenReturn(true);
        consumer.onEvent(envelope);
        verify(consumed).save(any()); // 仍然只有第一次的那一次 save
    }

    @Test
    void consumerSkipsMalformedAndKeylessMessagesInsteadOfCrashing() {
        ConsumedEventRepository consumed =
                mock(ConsumedEventRepository.class);
        PaperPublishedEventConsumer consumer = new PaperPublishedEventConsumer(consumed, new ObjectMapper());

        consumer.onEvent("not-json-at-all");
        consumer.onEvent("{\"payload\":{}}");
        consumer.onEvent("{\"eventKey\":\"\"}");

        verify(consumed, never()).save(any());
    }

    @Test
    void consumerThrowsOnPoisonMessageSoErrorHandlerCanSendItToDLT() {
        ConsumedEventRepository consumed =
                mock(ConsumedEventRepository.class);
        PaperPublishedEventConsumer consumer = new PaperPublishedEventConsumer(consumed, new ObjectMapper());

        // 毒丸消息必须向错误处理器抛异常（重试 2 次 → DLT），而不是静默跳过——
        // "合法但业务处理失败"与"畸形消息"是两类问题，处理策略必须不同
        String poison = "{\"eventKey\":\"paper:9:POISON\",\"eventType\":\"PAPER_PUBLISHED\",\"payload\":{\"fail\":true}}";
        assertThrows(IllegalStateException.class, () -> consumer.onEvent(poison));
        verify(consumed, never()).save(any());
    }
}
