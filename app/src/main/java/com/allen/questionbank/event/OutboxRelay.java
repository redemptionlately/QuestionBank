package com.allen.questionbank.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 投递器：轮询 PENDING 事件发往 Kafka，发送成功才标记 SENT。
 *
 * 投递语义是"至少一次"：send 不确定成功时保持 PENDING，下轮重试。
 * 重复消息由消费端 PaperPublishedEventConsumer 的唯一键挡住。
 * 不丢靠 outbox + 至少一次，不重靠消费幂等——两者合起来才是"不丢不重"。
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper;
    private final String topic;
    private final int maxAttempts;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper mapper,
                       @Value("${app.outbox.topic:qb-events}") String topic,
                       @Value("${app.outbox.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.mapper = mapper;
        this.topic = topic;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> pending = repository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING);
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(topic, event.eventKey(), wrap(event)).get();
                event.markSent();
            } catch (InterruptedException relayInterrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception sendFailure) {
                event.markFailed(String.valueOf(sendFailure.getMessage()));
                if (event.getRetryCount() >= maxAttempts) {
                    log.error("outbox 事件超过最大重试次数，需要人工介入: key={}, error={}",
                            event.eventKey(), sendFailure.getMessage());
                } else {
                    event.requeue();
                }
            }
        }
        // markSent / markFailed 是托管实体的修改，事务提交时由 JPA 脏检查落库
    }

    /** 统一信封：eventKey 独立于 payload，消费端不解析业务 JSON 就能做幂等判断。 */
    private String wrap(OutboxEvent event) throws Exception {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventKey", event.eventKey());
        envelope.put("eventType", event.getEventType());
        envelope.set("payload", mapper.readTree(event.getPayloadJson()));
        return mapper.writeValueAsString(envelope);
    }
}
