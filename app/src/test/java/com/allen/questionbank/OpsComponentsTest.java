package com.allen.questionbank;

import com.allen.questionbank.bank.PaperResponse;
import com.allen.questionbank.cache.LocalPublishedPaperCache;
import com.allen.questionbank.common.ExpiringCache;
import com.allen.questionbank.common.RequestMetrics;
import com.allen.questionbank.ops.DatabaseLatencyHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 运维与缓存组件的单元测试：探针降级、本地缓存命中语义、指标计数。 */
class OpsComponentsTest {

    @Test
    void healthIndicatorReportsUpWithLatencyOnHealthyDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        DatabaseLatencyHealthIndicator indicator = new DatabaseLatencyHealthIndicator(jdbc);

        var health = indicator.health();

        assertEquals("UP", health.getStatus().getCode());
        assertNotNull(health.getDetails().get("latencyMs"), "探针必须带出实测延迟");
        assertEquals(500L, health.getDetails().get("thresholdMs"));
    }

    @Test
    void healthIndicatorReportsDownWhenDatabaseIsUnreachable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new IllegalStateException("connection refused"));
        DatabaseLatencyHealthIndicator indicator = new DatabaseLatencyHealthIndicator(jdbc);

        var health = indicator.health();

        assertEquals("DOWN", health.getStatus().getCode(),
                "数据库不可达时 readiness 必须为 DOWN，让编排系统把实例摘掉");
    }

    @Test
    void localCacheTracksHitAndMissAndEviction() {
        LocalPublishedPaperCache cache = new LocalPublishedPaperCache(new ExpiringCache<>(Duration.ofMinutes(2)));
        List<PaperResponse> value = List.of(new PaperResponse(1L, 1L, 1, "t", "PUBLISHED", Instant.now()));

        assertSame(value, cache.getOrLoad("published", () -> value));
        assertEquals(0, cache.stats().hits(), "首次加载是未命中");
        assertEquals(1, cache.stats().misses());

        assertSame(value, cache.getOrLoad("published", () -> value));
        assertEquals(1, cache.stats().hits(), "第二次必须命中");
        assertEquals(0.5, cache.stats().hitRate(), 1e-9);
        assertEquals("local", cache.stats().backend());

        cache.evict("published");
        assertSame(value, cache.getOrLoad("published", () -> value));
        assertEquals(2, cache.stats().misses(), "淘汰之后再次读取应重新加载");
    }

    @Test
    void metricsCountRequestsFailuresAndLatency() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.request();
        metrics.request();
        metrics.failure();
        metrics.latency(1_000_000L);

        assertEquals(2, metrics.requests());
        assertEquals(1, metrics.failures());
        assertEquals(1_000_000L, metrics.totalLatencyNanos());
    }

    @Test
    void metricsRecordCountsFailureWithoutSwallowingException() {
        RequestMetrics metrics = new RequestMetrics();
        RuntimeException boom = new RuntimeException("boom");

        assertThrows(RuntimeException.class, () -> metrics.record(() -> { throw boom; }));

        assertEquals(1, metrics.requests());
        assertEquals(1, metrics.failures(), "record 必须计入失败但不能吞掉异常");
    }
}
