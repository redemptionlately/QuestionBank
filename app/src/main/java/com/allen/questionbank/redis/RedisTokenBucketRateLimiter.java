package com.allen.questionbank.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 令牌桶限流：限流状态放在 Redis，多个应用实例共享同一份配额，
 * 解决本地固定窗口在"多实例部署时限流被实例数放大"的问题。
 *
 * 令牌数以"毫令牌"整数存储（capacity * 1000），避免 Lua 里做浮点运算导致精度漂移。
 * 整个"读桶 → 按时间补充令牌 → 扣减 → 写回"必须在 Lua 里原子完成，
 * 拆成多条命令会出现并发超发。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
public class RedisTokenBucketRateLimiter {

    private static final String KEY_PREFIX = "qb:ratelimit:";

    private static final String TOKEN_BUCKET_LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 't', 'ts')
            local tokens = tonumber(data[1])
            local last = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              last = now
            end

            local delta = now - last
            if delta > 0 then
              tokens = math.min(capacity, tokens + delta * refill)
              last = now
            end

            local allowed = 0
            if tokens >= 1000 then
              tokens = tokens - 1000
              allowed = 1
            end

            redis.call('HSET', key, 't', tostring(math.floor(tokens)), 'ts', tostring(last))
            redis.call('PEXPIRE', key, ttl)
            return allowed
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
    }

    public boolean tryAcquire(String identity, int capacity, Duration window) {
        long windowMillis = Math.max(1, window.toMillis());
        long capacityMilli = (long) capacity * 1000L;
        // 每毫秒补充的毫令牌数，至少 1 避免长窗口下取整为 0 导致永不恢复
        long refillPerMilli = Math.max(1, capacityMilli / windowMillis);
        long ttlMillis = Math.max(windowMillis, capacityMilli / Math.max(1, refillPerMilli) * 2);

        Long allowed = redis.execute(script,
                List.of(KEY_PREFIX + identity),
                String.valueOf(capacityMilli),
                String.valueOf(refillPerMilli),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(ttlMillis));
        return allowed != null && allowed == 1L;
    }
}
