package com.allen.questionbank;

import com.allen.questionbank.bank.PaperResponse;
import com.allen.questionbank.cache.PublishedPaperCache;
import com.allen.questionbank.cache.RedisPublishedPaperCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis 共享缓存证据测试：默认关闭，设置 REDIS_EVIDENCE=true 且本地 Redis 可用后启用。
 * 覆盖三件事：命中路径、发布后事务提交再淘汰、Redis 故障降级到数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "redis"})
@EnabledIfEnvironmentVariable(named = "REDIS_EVIDENCE", matches = "true")
class RedisCacheIntegrationTest {

    private static final String CACHE_KEY = "qb:cache:published";

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired PublishedPaperCache cache;

    @BeforeEach
    void clearCache() {
        redis.delete(CACHE_KEY);
    }

    @Test
    void secondPublishedReadIsServedFromRedis() throws Exception {
        String student = login("student", "student123");
        long hitsBefore = cache.stats().hits();

        mvc.perform(get("/api/papers/published").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(CACHE_KEY)),
                "第一次读取（未命中）之后 Redis 里必须有缓存 key");

        mvc.perform(get("/api/papers/published").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        assertTrue(cache.stats().hits() > hitsBefore, "第二次读取必须命中 Redis 缓存");
        System.out.println("[evidence] redis cache backend=" + cache.stats().backend()
                + ", hitRate=" + cache.stats().hitRate());
    }

    @Test
    void publishEvictsCacheOnlyAfterCommit() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("student", "student123");

        mvc.perform(get("/api/papers/published").header("Authorization", bearer(student)))
                .andExpect(status().isOk());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(CACHE_KEY)), "前置条件：缓存已建立");

        String bankBody = mvc.perform(post("/api/admin/banks")
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Redis Evict Bank\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bankId = com.jayway.jsonpath.JsonPath.parse(bankBody).read("$.id", Long.class);

        String paperBody = mvc.perform(post("/api/admin/banks/{id}/versions", bankId)
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"Redis Evict Paper","questions":[
                                  {"prompt":"q","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":1}
                                ]}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long paperId = com.jayway.jsonpath.JsonPath.parse(paperBody).read("$.id", Long.class);

        mvc.perform(post("/api/admin/versions/{id}/publish", paperId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertFalse(Boolean.TRUE.equals(redis.hasKey(CACHE_KEY)),
                "发布事务提交之后缓存必须被淘汰，否则学生会读到过期的已发布列表");
    }

    @Test
    void cacheFallsBackToDatabaseWhenRedisIsDown() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("simulated outage"));

        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .build();
        RedisPublishedPaperCache degrading = new RedisPublishedPaperCache(broken, mapper, Duration.ofMinutes(1));

        List<PaperResponse> fromDatabase = List.of(
                new PaperResponse(1L, 1L, 1, "db-value", "PUBLISHED", Instant.now()));
        List<PaperResponse> result = degrading.getOrLoad("published", () -> fromDatabase);

        assertSame(fromDatabase, result, "Redis 宕机时必须降级到数据库返回结果，而不是让接口 500");
        assertDoesNotThrow(() -> degrading.evict("published"), "淘汰失败也不能向上抛异常");
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.token", String.class);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
