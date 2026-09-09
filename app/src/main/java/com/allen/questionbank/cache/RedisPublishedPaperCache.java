package com.allen.questionbank.cache;

import com.allen.questionbank.bank.PaperResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Redis 后端 cache-aside：多实例共享同一份缓存，写入带 TTL 抖动避免同时过期。
 *
 * <p>单飞（singleflight）防击穿：miss 后按 key 加锁 + 双检，热点 key 过期瞬间
 * 本实例内只有一个线程执行 loader 打库，其余线程等锁后直接读到回填结果。
 * 锁对象表按 key 数量增长——当前 key 空间是单热点 key（发布试卷），残余风险可忽略；
 * 若未来 key 数量爆炸，应换分段锁或升级为 Redis 分布式锁。跨实例仍可能同时 miss
 * （残余并发 = 实例数上限），由 TTL 抖动 + 数据库兜底。
 *
 * <p>降级策略：Redis 读失败、反序列化失败或写失败时一律回落到数据库，不让接口 500。
 */
@Component
@ConditionalOnProperty(name = "app.cache.backend", havingValue = "redis")
public class RedisPublishedPaperCache implements PublishedPaperCache {

    private static final String PREFIX = "qb:cache:";
    private static final TypeReference<List<PaperResponse>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration baseTtl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    /** 单飞锁表：key → 锁对象。容量 = 访问过的 distinct key 数（当前为 1）。 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public RedisPublishedPaperCache(StringRedisTemplate redis, ObjectMapper mapper,
                                    @Value("${app.cache.ttl:PT2M}") Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.baseTtl = ttl;
    }

    @Override
    public List<PaperResponse> getOrLoad(String key, Supplier<List<PaperResponse>> loader) {
        String redisKey = PREFIX + key;
        List<PaperResponse> fast = readThrough(redisKey);
        if (fast != null) return fast;
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // 双检：等锁期间可能已有并发线程完成回填
            List<PaperResponse> recheck = readThrough(redisKey);
            if (recheck != null) return recheck;
            misses.incrementAndGet();
            List<PaperResponse> loaded = loader.get();
            if (loaded != null) {
                try {
                    redis.opsForValue().set(redisKey, mapper.writeValueAsString(loaded), jitteredTtl());
                } catch (Exception writeFailure) {
                    failures.incrementAndGet();
                }
            }
            return loaded;
        }
    }

    /** 命中返回值并计 hit；miss 或读失败返回 null（失败计 failures）。 */
    private List<PaperResponse> readThrough(String redisKey) {
        try {
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null && !cached.isBlank()) {
                hits.incrementAndGet();
                return mapper.readValue(cached, LIST_TYPE);
            }
        } catch (Exception readFailure) {
            failures.incrementAndGet();
        }
        return null;
    }

    @Override
    public void evict(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (Exception evictFailure) {
            failures.incrementAndGet();
        }
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), "redis");
    }

    public long failures() {
        return failures.get();
    }

    private Duration jitteredTtl() {
        long seconds = Math.max(1, baseTtl.toSeconds());
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, seconds / 5));
        return baseTtl.plusSeconds(jitter);
    }
}
