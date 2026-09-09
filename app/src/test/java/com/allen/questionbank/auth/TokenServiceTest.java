package com.allen.questionbank.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenService 两条存储路径的行为：
 * local（默认，JVM 内存）与 redis（多实例共享）。
 * Redis 分支用 Mockito 桩而不依赖真 Redis——单元测试必须能在零外部依赖下跑。
 */
class TokenServiceTest {

    private static UserAccount user() {
        UserAccount user = new UserAccount("alice", "hash", Role.STUDENT);
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    @Test
    void localStoreIssuesAndResolvesToken() {
        TokenService service = new TokenService(Duration.ofHours(8), "local", providerOf(mock(StringRedisTemplate.class)));
        assertEquals("local", service.storeType());

        String token = service.issue(user());
        TokenService.TokenRecord record = service.resolve(token);

        assertNotNull(record);
        assertEquals(7L, record.userId());
        assertEquals("alice", record.username());
        assertEquals(Role.STUDENT, record.role());
    }

    @Test
    void localStoreRejectsUnknownToken() {
        TokenService service = new TokenService(Duration.ofHours(8), "local", providerOf(mock(StringRedisTemplate.class)));
        assertNull(service.resolve("not-a-token"));
        assertNull(service.resolve(""));
        assertNull(service.resolve(null));
    }

    @Test
    void localStoreRejectsExpiredToken() throws Exception {
        TokenService service = new TokenService(Duration.ofMillis(50), "local", providerOf(mock(StringRedisTemplate.class)));
        String token = service.issue(user());
        Thread.sleep(80);
        assertNull(service.resolve(token), "过期 token 必须失效");
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisStoreWritesTokenWithPrefixAndTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        TokenService service = new TokenService(Duration.ofMinutes(30), "redis", providerOf(redis));
        assertEquals("redis", service.storeType());

        String token = service.issue(user());
        verify(ops).set(eq("qb:token:" + token), anyString(), eq(Duration.ofMinutes(30)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisStoreResolvesTokenWrittenByAnotherInstance() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // 模拟"另一个实例写进去的"token：expiresAt 在未来
        when(ops.get("qb:token:shared")).thenReturn(
                "42|bob|ADMIN|" + Instant.now().plusSeconds(600).toEpochMilli());

        TokenService service = new TokenService(Duration.ofHours(8), "redis", providerOf(redis));
        TokenService.TokenRecord record = service.resolve("shared");

        assertNotNull(record, "Redis 里存在的 token 任何实例都应能解析——这就是水平扩容的前提");
        assertEquals(42L, record.userId());
        assertEquals("bob", record.username());
        assertEquals(Role.ADMIN, record.role());
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisStoreRejectsMissingExpiredAndMalformedPayload() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        TokenService service = new TokenService(Duration.ofHours(8), "redis", providerOf(redis));

        when(ops.get("qb:token:absent")).thenReturn(null);
        assertNull(service.resolve("absent"), "Redis 里没有就是没登录过");

        when(ops.get("qb:token:stale")).thenReturn(
                "1|bob|ADMIN|" + Instant.now().minusSeconds(1).toEpochMilli());
        assertNull(service.resolve("stale"), "payload 已过期必须拒绝（TTL 之外的第二道防线）");

        when(ops.get("qb:token:garbage")).thenReturn("only-two|parts");
        assertNull(service.resolve("garbage"), "畸形 payload 不能抛异常，按无效 token 处理");
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisRequestedWithoutTemplateFallsBackToLocal() {
        ObjectProvider<StringRedisTemplate> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);

        TokenService service = new TokenService(Duration.ofHours(8), "redis", empty);
        assertEquals("local", service.storeType(), "拿不到 StringRedisTemplate 就退化成本地，不让登录在启动期炸掉");

        String token = service.issue(user());
        assertNotNull(service.resolve(token));
        verify(empty).getIfAvailable();
    }

    @SuppressWarnings("unchecked")
    @Test
    void localStoreNeverTouchesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TokenService service = new TokenService(Duration.ofHours(8), "local", providerOf(redis));
        service.issue(user());
        verify(redis, never()).opsForValue();
    }
}
