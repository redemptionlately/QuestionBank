package com.allen.questionbank.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布事件消费者。Kafka 是至少一次投递，同一事件可能被投多次；
 * 消费幂等靠 consumed_event 表的唯一键（uk_consumed_event_key）：
 * 重复消息插入冲突即跳过，业务效果等效"恰好一次"。
 * 幂等判断只依赖信封里的 eventKey，不解析业务 payload——消费逻辑换业务也不变。
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaperPublishedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaperPublishedEventConsumer.class);

    private final ConsumedEventRepository consumed;
    private final ObjectMapper mapper;

    public PaperPublishedEventConsumer(ConsumedEventRepository consumed, ObjectMapper mapper) {
        this.consumed = consumed;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${app.outbox.topic:qb-events}", groupId = "${app.kafka.group-id:question-bank}")
    @Transactional
    public void onEvent(String message) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(message);
        } catch (Exception malformedMessage) {
            log.warn("无法解析的消息，跳过: {}", message);
            return;
        }
        String eventKey = envelope.path("eventKey").asText(null);
        if (eventKey == null || eventKey.isBlank()) {
            log.warn("消息缺少 eventKey，无法做幂等判断，跳过: {}", message);
            return;
        }
        // 毒丸消息（业务处理失败的替身）：向错误处理器抛异常 → 重试 2 次 → 进 DLT 死信主题。
        // 注意与"畸形消息跳过"的区别：跳过适用于无法处理的坏消息；DLT 适用于合法但处理失败的消息。
        if (envelope.path("payload").path("fail").asBoolean(false)) {
            throw new IllegalStateException("业务处理失败（演示 DLT 路径）: " + eventKey);
        }
        if (consumed.existsByEventKey(eventKey)) {
            log.info("重复消息，按幂等跳过: {}", eventKey);
            return;
        }
        consumed.save(new ConsumedEvent(eventKey));
        log.info("事件已消费: {} payload={}", eventKey, envelope.path("payload"));
    }
}
