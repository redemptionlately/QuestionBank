package com.allen.questionbank.cache;

import com.allen.questionbank.bank.PaperResponse;
import com.allen.questionbank.common.ExpiringCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 默认后端：进程内 TTL 缓存。单实例基线，不做分布式失效。 */
@Component
@ConditionalOnProperty(name = "app.cache.backend", havingValue = "local", matchIfMissing = true)
public class LocalPublishedPaperCache implements PublishedPaperCache {

    private final ExpiringCache<String, List<PaperResponse>> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public LocalPublishedPaperCache(ExpiringCache<String, List<PaperResponse>> cache) {
        this.cache = cache;
    }

    @Override
    public List<PaperResponse> getOrLoad(String key, Supplier<List<PaperResponse>> loader) {
        List<PaperResponse> cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        return cache.getOrLoad(key, loader);
    }

    @Override
    public void evict(String key) {
        cache.evict(key);
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.get(), misses.get(), "local");
    }

    public Duration ttl() {
        return Duration.ofMinutes(2);
    }
}
