package com.allen.questionbank;

import com.allen.questionbank.event.OutboxEvent;
import com.allen.questionbank.event.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Outbox → Kafka → 消费幂等的端到端证据（开关 KAFKA_EVIDENCE=true，需要本地 Kafka 9092）。
 * 验证三件事：事务内事件与业务一起落库、投递器把 PENDING 送到 SENT、重复投递被消费端唯一键挡住。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "kafka"})
@EnabledIfEnvironmentVariable(named = "KAFKA_EVIDENCE", matches = "true")
class KafkaOutboxIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper mapper;

    @Test
    void publishEnqueuesOutboxRelaySendsAndConsumerDeduplicates() throws Exception {
        String admin = login("admin", "admin123");

        String bankBody = mvc.perform(post("/api/admin/banks")
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Outbox Bank\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        long bankId = com.jayway.jsonpath.JsonPath.parse(bankBody).read("$.id", Long.class);

        String paperBody = mvc.perform(post("/api/admin/banks/{id}/versions", bankId)
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Outbox Paper","questions":[
                                  {"prompt":"q","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":1}
                                ]}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        long paperId = com.jayway.jsonpath.JsonPath.parse(paperBody).read("$.id", Long.class);

        mvc.perform(post("/api/admin/versions/{id}/publish", paperId)
                        .header("Authorization", bearer(admin)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        String expectedKey = "paper:" + paperId + ":PAPER_PUBLISHED";

        // 1. 发布事务里 outbox 与业务数据一起落库（eventKey 确定性）
        // 注意：不断言 PENDING——relay 轮询间隔 500ms，断言执行时可能已被推进到 SENT，
        // 强断言 PENDING 是与调度器的竞态；"发布提交后事件必须存在"才是不依赖时序的事实。
        OutboxEvent event = Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                () -> outbox.findAll().stream()
                        .filter(e -> e.getAggregateId().equals(paperId))
                        .findFirst().orElse(null),
                java.util.Objects::nonNull);
        assertEquals(expectedKey, event.eventKey());

        // 2. 投递器把 PENDING 推到 SENT
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(OutboxEvent.STATUS_SENT,
                        outbox.findById(event.getId()).orElseThrow().getStatus(),
                        "投递器必须把事件标记为 SENT"));

        // 3. 消费端写入 consumed_event（等最早 offset 消费完）
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertTrue(consumedCount(expectedKey) >= 1, "消费者必须记录已消费事件"));

        // 4. 同一事件手动重投一次（模拟 at-least-once 重复）→ 幂等表不增加
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventKey", expectedKey);
        envelope.put("eventType", "PAPER_PUBLISHED");
        envelope.set("payload", mapper.createObjectNode().put("replayed", true));
        kafkaTemplate.send("qb-events", expectedKey, mapper.writeValueAsString(envelope)).get();

        int countAfterFirstDelivery = consumedCount(expectedKey);
        Thread.sleep(3000);
        assertEquals(countAfterFirstDelivery, consumedCount(expectedKey),
                "重复投递必须被消费端唯一键挡住，consumed_event 不允许出现第二条");
        System.out.println("[evidence] outbox→kafka→消费幂等 全链路通过，eventKey=" + expectedKey);
    }

    private int consumedCount(String eventKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_key = ?", Integer.class, eventKey);
        return count == null ? 0 : count;
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.token", String.class);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
