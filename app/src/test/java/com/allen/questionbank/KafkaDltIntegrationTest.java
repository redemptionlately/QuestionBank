package com.allen.questionbank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死信队列端到端证据（KAFKA_EVIDENCE=true，需本地 Kafka 9092）：
 * 合法信封 + 毒丸 payload（fail:true）→ 消费者抛异常 → 错误处理器重试 2 次 → 原消息进 qb-events.DLT。
 * 同时验证：失败消息绝不写 consumed_event（幂等表只登记成功消费）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "kafka"})
@EnabledIfEnvironmentVariable(named = "KAFKA_EVIDENCE", matches = "true")
class KafkaDltIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void poisonMessageIsRetriedThenSentToDeadLetterTopic() {
        String eventKey = "paper:9999:POISON_DEMO";
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventKey", eventKey);
        envelope.put("eventType", "PAPER_PUBLISHED");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("fail", true);
        envelope.set("payload", payload);

        kafkaTemplate.send("qb-events", eventKey, envelope.toString()).join();
        System.out.println("[evidence] 毒丸消息已投递到 qb-events，等待重试后进 DLT...");

        // 轮询 DLT 主题：错误处理器重试 2 次（200ms 间隔）后由 DeadLetterPublishingRecoverer 投递
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ConsumerRecord<String, String> dlt = readFirstFromDlt(eventKey);
            assertTrue(dlt != null, "毒丸消息必须出现在 qb-events.DLT 死信主题");
            assertTrue(dlt.value().contains(eventKey), "DLT 消息必须保留原 eventKey");
            System.out.println("[evidence] 消息已进死信主题 qb-events.DLT，headers 含原始异常信息");
        });

        // 失败消息绝不能登记为"已消费"
        Integer recorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_key = ?", Integer.class, eventKey);
        assertEquals(0, recorded, "业务处理失败的消息不允许写 consumed_event（幂等表只登记成功消费）");
        System.out.println("[evidence] consumed_event 无该消息记录：失败 ≠ 消费成功");
    }

    /** 用独立消费组直接读 DLT 主题，绕开应用内的消费者（验证的是"消息真实落盘在 Kafka"）。 */
    private ConsumerRecord<String, String> readFirstFromDlt(String eventKey) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-verifier-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("qb-events.DLT"));
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(eventKey)) {
                        return record;
                    }
                }
            }
        }
        return null;
    }
}
