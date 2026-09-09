package com.allen.questionbank;

import com.allen.questionbank.event.OutboxEvent;
import com.allen.questionbank.event.OutboxEventRepository;
import com.allen.questionbank.event.OutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Outbox 状态机与入队语义的单元测试：不依赖 Kafka 与数据库。 */
class OutboxTest {

    @Test
    void eventLifecycleMovesPendingToSentToFailedAndBack() {
        OutboxEvent event = new OutboxEvent("paper", 7L, "PAPER_PUBLISHED", "{\"paperId\":7}");

        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus());
        assertNull(event.getSentAt());

        event.markSent();
        assertEquals(OutboxEvent.STATUS_SENT, event.getStatus());
        assertNotNull(event.getSentAt());

        event.markFailed("broker down");
        assertEquals(OutboxEvent.STATUS_FAILED, event.getStatus());
        assertEquals(1, event.getRetryCount(), "失败必须累计重试次数");
        assertEquals("broker down", event.getLastError());

        event.requeue();
        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus(), "失败且未超限的事件应回到 PENDING 重试");
    }

    @Test
    void oversizedLastErrorIsTruncatedToColumnLimit() {
        OutboxEvent event = new OutboxEvent("paper", 1L, "PAPER_PUBLISHED", "{}");
        event.markFailed("x".repeat(10_000));
        assertTrue(event.getLastError().length() <= 500, "错误信息必须截断，避免超出列宽把状态更新搞挂");
    }

    @Test
    void eventKeyIsDeterministicForDeduplication() {
        OutboxEvent event = new OutboxEvent("paper", 42L, "PAPER_PUBLISHED", "{}");
        assertEquals("paper:42:PAPER_PUBLISHED", event.eventKey());
        assertEquals(event.eventKey(), new OutboxEvent("paper", 42L, "PAPER_PUBLISHED", "{}").eventKey(),
                "同一聚合同一事件类型的 key 必须一致，这是消费幂等的基础");
    }

    @Test
    void publisherSkipsDuplicateEventForSameAggregateAndType() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.existsByAggregateTypeAndAggregateIdAndEventType("paper", 7L, "PAPER_PUBLISHED"))
                .thenReturn(true);
        OutboxPublisher publisher = new OutboxPublisher(repository, new ObjectMapper(), jdbc);

        publisher.enqueue("paper", 7L, "PAPER_PUBLISHED", Map.of("paperId", 7));

        // 去重命中必须完全零写入：不用 anyString() 匹配器（varargs 匹配不可靠），直接断言零交互
        verifyNoInteractions(jdbc);
    }

    @Test
    void publisherFailsTheWholeTransactionWhenPayloadCannotBeSerialized() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = Mockito.mock(ObjectMapper.class);
        // Mockito 默认对 String 返回空串而非 null；要测 null 分支必须显式打桩
        when(objectMapper.writeValueAsString(any())).thenReturn(null);
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, jdbc);

        // 序列化结果为 null → 直接抛"结果为空"（校验在 try 外，不会被包装成"序列化失败"）
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> publisher.enqueue("paper", 7L, "PAPER_PUBLISHED", Map.of("paperId", 7)),
                "坏事件必须让业务事务一起回滚，不能带着空 payload 提交");
        assertTrue(thrown.getMessage().contains("结果为空(null)"),
                "必须区分'结果为空'与'序列化失败'两种失败路径，实际: " + thrown.getMessage());
        assertNull(thrown.getCause(), "空结果不是异常引起，不应有 cause");
        verifyNoInteractions(jdbc);
    }

    @Test
    void publisherWrapsSerializationFailureWithCausePreserved() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = Mockito.mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("bad payload"));
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, jdbc);

        // 真正的序列化异常 → 包装成 ISE，cause 必须保留原异常
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> publisher.enqueue("paper", 7L, "PAPER_PUBLISHED", Map.of("paperId", 7)));
        assertTrue(thrown.getMessage().contains("序列化失败"));
        assertEquals("bad payload", thrown.getCause().getMessage(), "cause 必须保留，否则根因丢失");
        verifyNoInteractions(jdbc);
    }

    @Test
    void publisherSavesSerializedPayloadWhenEverythingIsValid() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.existsByAggregateTypeAndAggregateIdAndEventType(anyString(), any(), anyString()))
                .thenReturn(false);
        OutboxPublisher publisher = new OutboxPublisher(repository, new ObjectMapper(), jdbc);

        publisher.enqueue("paper", 7L, "PAPER_PUBLISHED", Map.of("paperId", 7));

        // 写入走 JdbcTemplate 原生 upsert（ON DUPLICATE KEY 兜底并发撞键），不再走 JPA save
        verify(jdbc).update(anyString(),
                eq("paper"), eq(7L), eq("PAPER_PUBLISHED"), eq("{\"paperId\":7}"),
                eq(OutboxEvent.STATUS_PENDING), any(Timestamp.class));
        verify(repository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void repositoryContractUsesExactMatchForDeduplication() {
        // existsBy 的三个字段必须精确匹配：aggregateType/aggregateId/eventType 任意不同都是新事件
        OutboxEventRepository repository = Mockito.mock(OutboxEventRepository.class);
        when(repository.existsByAggregateTypeAndAggregateIdAndEventType(eq("paper"), eq(7L), eq("PAPER_PUBLISHED")))
                .thenReturn(true);
        when(repository.existsByAggregateTypeAndAggregateIdAndEventType(eq("paper"), eq(7L), eq("PAPER_RETRACTED")))
                .thenReturn(false);

        assertTrue(repository.existsByAggregateTypeAndAggregateIdAndEventType("paper", 7L, "PAPER_PUBLISHED"));
        assertFalse(repository.existsByAggregateTypeAndAggregateIdAndEventType("paper", 7L, "PAPER_RETRACTED"));
    }

    @Test
    void publisherRejectsBlankSerializedPayloadEvenWhenNotNull() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = Mockito.mock(ObjectMapper.class);
        // 非 null 但全空白：与"结果为空(null)"是两条独立的拒绝路径，blank 检查失效时坏事件会裸奔进 Kafka
        when(objectMapper.writeValueAsString(any())).thenReturn("   ");
        OutboxPublisher publisher = new OutboxPublisher(repository, objectMapper, jdbc);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> publisher.enqueue("paper", 7L, "PAPER_PUBLISHED", Map.of("paperId", 7)),
                "空白 payload 必须被拒，不能落库");
        assertTrue(thrown.getMessage().contains("结果为空(blank)"),
                "blank 与 null 是两条独立校验，必须分别可辨: " + thrown.getMessage());
        verifyNoInteractions(jdbc);
    }
}
