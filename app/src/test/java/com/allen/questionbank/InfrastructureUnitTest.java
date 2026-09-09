package com.allen.questionbank;

import com.allen.questionbank.common.ExpiringCache;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InfrastructureUnitTest {
    @Test void cacheLoadsOnceUntilEvicted() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        assertEquals("v", cache.getOrLoad("k", () -> { loads.incrementAndGet(); return "v"; }));
        assertEquals("v", cache.getOrLoad("k", () -> { loads.incrementAndGet(); return "v2"; }));
        assertEquals(1, loads.get());
        cache.evict("k");
        assertEquals("v2", cache.getOrLoad("k", () -> { loads.incrementAndGet(); return "v2"; }));
        assertEquals(2, loads.get());
    }
}
