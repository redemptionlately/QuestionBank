package com.allen.questionbank.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis 分布式锁（单实例 Redis 语义）。
 *
 * 三条硬规则：
 * 1. 加锁必须带唯一 token 且设置过期时间（SET key token NX PX ttl 原子完成），
 *    否则进程崩溃后锁永远不释放。
 * 2. 释放必须用 Lua 校验 token 再删：GET + DEL 分开做会删掉别人续上的锁。
 * 3. 即使有这把锁，写路径仍然需要数据库唯一键兜底——GC 停顿、时钟漂移、
 *    主从切换都可能让锁提前失效，此时两个调用方会同时持有锁（见 fencing 说明）。
 *
 * fencing token 由 Redis INCR 生成，跨实例单调递增；存储层应当拒绝旧 token 的写入。
 *
 * <p>为什么不做 watchdog 自动续期：正确性契约不依赖锁长期有效——规则 3 已保证锁失效后
 * 数据库唯一键兜底（RedisGuardedSubmitIntegrationTest 绕锁并发提交实证）。watchdog 用
 * 复杂度换"锁尽量不过期"，但掩盖不了竞态（进程假死时自动续期反而延长故障占锁）。
 * 锁过期的现实语义：拿不到锁 → 调用方回退无锁提交路径，仍由 DB 兜底，属设计选择而非缺口。
 */
@Component
@ConditionalOnProperty(name = "app.lock.backend", havingValue = "redis")
public class RedisLockService {

    private static final String KEY_PREFIX = "qb:lock:";
    private static final String FENCING_KEY = "qb:lock:fencing";

    /** 只有持有匹配 token 的调用方才能删除，保证不会误删别人的锁。 */
    private static final String RELEASE_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisLockService(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    }

    public Optional<LockHandle> tryLock(String name, Duration ttl) {
        String key = KEY_PREFIX + name;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        Long fencing = redis.opsForValue().increment(FENCING_KEY);
        return Optional.of(new LockHandle(this, key, token, fencing == null ? 0L : fencing));
    }

    public boolean unlock(String key, String token) {
        Long released = redis.execute(releaseScript, List.of(key), token);
        return released != null && released > 0;
    }

    public long currentFencingToken() {
        String value = redis.opsForValue().get(FENCING_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    public static final class LockHandle implements AutoCloseable {
        private final RedisLockService owner;
        private final String key;
        private final String token;
        private final long fencingToken;

        LockHandle(RedisLockService owner, String key, String token, long fencingToken) {
            this.owner = owner;
            this.key = key;
            this.token = token;
            this.fencingToken = fencingToken;
        }

        public String key() {
            return key;
        }

        public String token() {
            return token;
        }

        public long fencingToken() {
            return fencingToken;
        }

        public boolean unlock() {
            return owner.unlock(key, token);
        }

        @Override
        public void close() {
            unlock();
        }
    }
}
