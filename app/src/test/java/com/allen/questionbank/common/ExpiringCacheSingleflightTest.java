package com.allen.questionbank.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ExpiringCache 单飞（singleflight）与清扫行为：修复前并发 miss 会执行 N 次 loader。 */
class ExpiringCacheSingleflightTest {

    @Test
    void concurrentMissOnSameKeyLoadsExactlyOnce() throws Exception {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(2, TimeUnit.SECONDS);
                return cache.getOrLoad("hot-key", () -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(50); // 拉长加载窗口，让其余线程确定性地落在等锁路径上
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return "loaded-value";
                });
            }));
        }
        for (Future<String> f : futures) {
            assertEquals("loaded-value", f.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, loads.get(), "并发 miss 同一 key 必须只有一次 loader 执行");
        pool.shutdownNow();
    }

    @Test
    void expiredEntriesSweptOnNextLoad() throws Exception {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMillis(60));
        cache.getOrLoad("stale", () -> "old-value");
        Thread.sleep(100); // 等 stale 过期
        cache.getOrLoad("fresh", () -> "new-value");
        assertEquals(1, cache.size(), "加载时顺手清扫：过期项不应残留");
        assertNull(cache.get("stale"));
        assertEquals("new-value", cache.get("fresh"));
    }

    @Test
    void loaderFailurePropagatesAndCachesNothing() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMinutes(1));
        assertThrows(IllegalStateException.class,
                () -> cache.getOrLoad("k", () -> { throw new IllegalStateException("boom"); }));
        assertNull(cache.get("k"));
        assertEquals(0, cache.size());
    }

    @Test
    void getOrLoadReturnsCachedValueWithoutLoader() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMinutes(1));
        cache.getOrLoad("k", () -> "v1");
        assertTrue(cache.getOrLoad("k", () -> {
            throw new AssertionError("命中时不应执行 loader");
        }).equals("v1"));
    }

    @Test
    void expiredEntriesAreInvisibleToPlainGet() throws Exception {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMillis(100));
        cache.getOrLoad("k", () -> "v");
        assertEquals("v", cache.get("k"));
        Thread.sleep(250); // 确保跨过 TTL（150ms 余量，避开调度抖动）
        assertNull(cache.get("k"), "过期条目必须立刻不可见——get 路径自己的过期判断是缓存正确性的最后防线");
    }

    @Test
    void sweepNeverDiscardsFreshEntries() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMinutes(1));
        cache.getOrLoad("fresh", () -> "v1");
        cache.getOrLoad("trigger", () -> "v2"); // 第二次 miss 触发全表清扫，此时 fresh 仍新鲜
        assertEquals("v1", cache.get("fresh"), "清扫只摘过期项，新鲜条目不得误伤");
        assertEquals("v2", cache.get("trigger"));
    }
}
