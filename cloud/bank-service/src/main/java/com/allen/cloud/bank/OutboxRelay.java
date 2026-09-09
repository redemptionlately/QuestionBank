package com.allen.cloud.bank;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发件箱投递器：把 outbox_event 里未发送的行投到 Kafka，成功后标记 sent。
 *
 * <p>两个必须说清的性质：
 *
 * <ol>
 *   <li><b>至少一次</b>：发送成功但标记 sent 之前进程挂掉，重启后会再发一次。
 *       所以消费端必须按 eventKey 幂等——这是 outbox 模式的另一半，缺了它这个模式不成立。</li>
 *   <li><b>投递失败不阻塞业务</b>：relay 与发布接口是两条独立的事务，
 *       Kafka 挂了只是消息积压在表里，发布功能不受影响（这正是相对"事务里直接 send"的优势）。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public OutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${app.kafka.topic:qb-paper-events}") String topic) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outbox.findTop50BySentFalseOrderByIdAsc();
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(topic, event.getEventKey(), event.getPayload())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                event.markSent();
                outbox.save(event);
                log.info("[outbox] 已投递 eventKey={} type={}", event.getEventKey(), event.getEventType());
            } catch (Exception e) {
                // Kafka 不可用：留在本轮循环之外，下个周期重试。不吞异常、也不让调度器挂掉。
                log.warn("[outbox] 投递失败，下轮重试 eventKey={} 原因={}", event.getEventKey(), e.getMessage());
                return;
            }
        }
    }
}
