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
 * 令牌数以浮点存进 Redis（tostring 双精度），续杯速率 = capacity/window 是真实比例。
 * 历史教训：曾用"毫令牌整型（capacity*1000）+ Math.max(1, refillPerMilli)"避免取整为 0，
 * 结果那个 max(1,...) 下限把实际续杯抬到 >=1 令牌/秒、与配置窗口无关——
 * "PT10M 配 3 次"被架空成"3 突发 + 1/秒"（2026-09-09 多实例对照实验实测抓出后修复，
 * 回归用例 longWindowDoesNotRefillAtOneTokenPerSecond）。
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
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            -- 令牌数用浮点写回：math.floor 会把每次调用的小数余量丢弃，
            -- 与"毫令牌整型化"一样会让长窗口的真实续杯比例失真
            redis.call('HSET', key, 't', tostring(tokens), 'ts', tostring(last))
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
        // 每毫秒补充的令牌数（浮点，不取整）：capacity/window 才是配置声明的真实速率。
        // 旧实现的毫令牌整型 + Math.max(1,...) 下限会无视窗口把续杯抬到 >=1 令牌/秒，
        // 长窗口配置被架空（见类注释里的回归用例）。
        double refillPerMilli = (double) capacity / windowMillis;
        // 从空桶恢复满桶恰需 windowMillis（capacity / (capacity/window)），TTL 给两倍余量。
        // 旧格式 key 里残留的毫令牌值（如 3000）会被 math.min(capacity,...) 自动钳回，无需迁移。
        long ttlMillis = 2 * windowMillis;

        Long allowed = redis.execute(script,
                List.of(KEY_PREFIX + identity),
                String.valueOf(capacity),
                String.valueOf(refillPerMilli),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(ttlMillis));
        return allowed != null && allowed == 1L;
    }
}
