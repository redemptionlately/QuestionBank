package com.allen.questionbank.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(Instant started, AtomicInteger count) {}
    private final int capacity;
    private final Duration window;
    private final RequestMetrics metrics;
    private final ConcurrentHashMap<String, Window> clients = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.capacity:300}") int capacity,
                           @Value("${app.rate-limit.window:PT1M}") Duration window,
                           RequestMetrics metrics) {
        this.capacity = Math.max(1, capacity);
        this.window = window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        metrics.request();
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        Instant now = Instant.now();
        Window current = clients.compute(key, (ignored, old) -> {
            if (old == null || old.started().plus(window).isBefore(now)) return new Window(now, new AtomicInteger(1));
            old.count().incrementAndGet(); return old;
        });
        if (current.count().get() > capacity) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, window.toSeconds())));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁\"}");
            metrics.failure();
            metrics.latency(System.nanoTime() - startedNanos);
            return;
        }
        try { chain.doFilter(request, response); }
        catch (RuntimeException | ServletException | IOException error) { metrics.failure(); throw error; }
        finally { metrics.latency(System.nanoTime() - startedNanos); }
    }
}
