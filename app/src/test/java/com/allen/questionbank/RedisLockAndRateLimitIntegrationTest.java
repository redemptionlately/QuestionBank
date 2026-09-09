package com.allen.questionbank;

import com.allen.questionbank.redis.RedisLockService;
import com.allen.questionbank.redis.RedisTokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Redis 分布式锁与令牌桶限流的证据测试（开关 REDIS_EVIDENCE=true）。
 * 验证的是"锁的语义"而不是"代码跑通"：互斥、TTL 自动释放、误删防护、fencing 单调、桶容量与恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "redis"})
@TestPropertySource(properties = {
        "app.rate-limit.backend=redis",
        "app.rate-limit.capacity=3",
        "app.rate-limit.window=PT10S"
})
@EnabledIfEnvironmentVariable(named = "REDIS_EVIDENCE", matches = "true")
class RedisLockAndRateLimitIntegrationTest {

    @Autowired RedisLockService lockService;
    @Autowired RedisTokenBucketRateLimiter rateLimiter;
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;

    @Test
    void lockIsExclusiveAndCanOnlyBeReleasedByItsHolder() {
        String name = "evidence-" + UUID.randomUUID();
        Optional<RedisLockService.LockHandle> first = lockService.tryLock(name, Duration.ofSeconds(10));
        assertTrue(first.isPresent(), "首次加锁必须成功");

        assertFalse(lockService.tryLock(name, Duration.ofSeconds(10)).isPresent(),
                "持锁期间其他调用方必须拿不到锁");

        assertFalse(lockService.unlock(first.get().key(), "not-my-token"),
                "Lua 必须校验 token，错误 token 不能释放别人的锁");
        assertTrue(lockService.tryLock(name, Duration.ofSeconds(10)).isEmpty(),
                "错误 token 释放失败后锁仍应被持有");

        assertTrue(first.get().unlock(), "持有者必须能正常释放");
        assertTrue(lockService.tryLock(name, Duration.ofSeconds(10)).isPresent(),
                "释放之后其他调用方应能拿到锁");
        System.out.println("[evidence] 分布式锁：互斥=通过，误删防护=通过，持有者释放=通过");
    }

    @Test
    void lockExpiresAutomaticallyAfterTtl() throws Exception {
        String name = "ttl-" + UUID.randomUUID();
        assertTrue(lockService.tryLock(name, Duration.ofMillis(500)).isPresent());
        Thread.sleep(900);
        assertTrue(lockService.tryLock(name, Duration.ofSeconds(5)).isPresent(),
                "TTL 到期后锁必须自动释放，否则进程崩溃会留下死锁");
        System.out.println("[evidence] 分布式锁：TTL 自动释放=通过");
    }

    @Test
    void fencingTokenIsMonotonicallyIncreasingAcrossAcquisitions() {
        long before = lockService.currentFencingToken();
        String name = "fence-" + UUID.randomUUID();
        try (RedisLockService.LockHandle first = lockService.tryLock(name, Duration.ofSeconds(5)).orElseThrow()) {
            long firstToken = first.fencingToken();
            first.unlock();
            try (RedisLockService.LockHandle second = lockService.tryLock(name, Duration.ofSeconds(5)).orElseThrow()) {
                assertTrue(second.fencingToken() > firstToken,
                        "fencing token 必须单调递增，存储层据此拒绝过期持有者的写入");
            }
        }
        assertTrue(lockService.currentFencingToken() > before);
        System.out.println("[evidence] fencing token 单调递增=通过，当前值=" + lockService.currentFencingToken());
    }

    @Test
    void tokenBucketAdmitsExactlyCapacityThenRejects() {
        String key = "bucket-" + UUID.randomUUID();
        int capacity = 5;
        int admitted = 0;
        for (int i = 0; i < capacity + 3; i++) {
            if (rateLimiter.tryAcquire(key, capacity, Duration.ofSeconds(10))) {
                admitted++;
            }
        }
        assertEquals(capacity, admitted, "令牌桶应恰好放行 capacity 个请求，多一次都不行");
        System.out.println("[evidence] 令牌桶：容量 " + capacity + "，实际放行 " + admitted);
    }

    @Test
    void tokenBucketRefillsAfterWindowElapses() throws Exception {
        String key = "refill-" + UUID.randomUUID();
        assertTrue(rateLimiter.tryAcquire(key, 1, Duration.ofSeconds(2)), "首个请求应放行");
        assertFalse(rateLimiter.tryAcquire(key, 1, Duration.ofSeconds(2)), "令牌耗尽后应拒绝");
        Thread.sleep(2500);
        assertTrue(rateLimiter.tryAcquire(key, 1, Duration.ofSeconds(2)), "窗口过后令牌必须补充回来");
        System.out.println("[evidence] 令牌桶：耗尽后按时间补充=通过");
    }

    @Test
    void longWindowDoesNotRefillAtOneTokenPerSecond() throws Exception {
        // 回归：旧实现 Math.max(1, capacityMilli/windowMillis) 的下限把续杯抬到 >=1 令牌/秒，
        // "capacity=2, window=PT10M" 实际变成"2 突发 + 1/秒"——多实例对照实验实测抓出（2026-09-09）。
        // 修复后续杯 = 2/600000 ≈ 0.0000033 令牌/ms，1.5s 内远不足 1 个令牌，必须仍然拒绝。
        // 旧代码跑本用例必红（1.5s 就能攒出 1.5 个令牌），新代码必须绿。
        String key = "refill-floor-" + UUID.randomUUID();
        assertTrue(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(10)), "第 1 个请求应放行");
        assertTrue(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(10)), "第 2 个请求应放行");
        assertFalse(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(10)), "配额耗尽后应拒绝");
        Thread.sleep(1500);
        assertFalse(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(10)),
                "长窗口 1.5s 内不应补充出 1 个令牌（续杯下限缺陷会在此放行）");
        System.out.println("[evidence] 长窗口续杯下限回归：1.5s 内不恢复令牌=通过");
    }

    @Test
    void sharedRateLimitReturns429OnceCapacityIsExhausted() throws Exception {
        String student = login("student", "student123");
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            int status = mvc.perform(get("/api/papers/published")
                            .header("Authorization", bearer(student)))
                    .andReturn().getResponse().getStatus();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertEquals(3, ok, "容量 3，前 3 次必须放行，实际 ok=" + ok);
        assertEquals(3, limited, "超出容量的请求必须返回 429，实际 429=" + limited);
        System.out.println("[evidence] 共享限流：放行 " + ok + " 次，429 " + limited + " 次");
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
