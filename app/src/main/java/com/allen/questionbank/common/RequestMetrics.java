package com.allen.questionbank.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class RequestMetrics {
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong totalLatencyNanos = new AtomicLong();

    public <T> T record(Supplier<T> action) {
        requests.incrementAndGet();
        long start = System.nanoTime();
        try { return action.get(); }
        catch (RuntimeException | Error error) { failures.incrementAndGet(); throw error; }
        finally { totalLatencyNanos.addAndGet(System.nanoTime() - start); }
    }
    public void request() { requests.incrementAndGet(); }
    public void failure() { failures.incrementAndGet(); }
    public void latency(long nanos) { totalLatencyNanos.addAndGet(nanos); }
    public long requests() { return requests.get(); }
    public long failures() { return failures.get(); }
    public long totalLatencyNanos() { return totalLatencyNanos.get(); }
}
