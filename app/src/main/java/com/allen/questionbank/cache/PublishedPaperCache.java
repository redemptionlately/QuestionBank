package com.allen.questionbank.cache;

import com.allen.questionbank.bank.PaperResponse;

import java.util.List;
import java.util.function.Supplier;

/**
 * 已发布试卷列表的 cache-aside 抽象。
 * 两种实现：进程内 TTL 缓存（默认基线）与 Redis 共享缓存（app.cache.backend=redis）。
 * 事实源始终是数据库，缓存可重建；任一实现故障时都必须降级到数据库而不是让接口报错。
 */
public interface PublishedPaperCache {

    List<PaperResponse> getOrLoad(String key, Supplier<List<PaperResponse>> loader);

    void evict(String key);

    CacheStats stats();

    record CacheStats(long hits, long misses, String backend) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
