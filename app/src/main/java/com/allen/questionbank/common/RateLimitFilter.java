package com.allen.questionbank.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地固定窗口限流（默认后端）。单实例有效；多实例部署时限流额度会被实例数放大，
 * 那种场景切到 app.rate-limit.backend=redis 走令牌桶。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "local", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(Instant started, AtomicInteger count) {}

    /** 每处理多少个请求做一次全表惰性清理。窗口 key 含 URI，攻击者可以用随机 URI 无限造 key，
     *  不清理就是内存泄漏（拒绝服务向量）；清理频率摊薄后单次成本可忽略。 */
    private static final int SWEEP_EVERY = 256;

    private final int capacity;
    private final Duration window;
    private final RequestMetrics metrics;
    private final ConcurrentHashMap<String, Window> clients = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();

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
        // 惰性清理必须在 compute 之外做：ConcurrentHashMap.compute 的 lambda 不允许再动
        // 其他映射（契约禁止，实测会 AssertionError）。removeIf 判据与 compute 的过期判据
        // 完全一致（isBefore），只会删 compute 自己也会换掉的那些窗口；极端并发下最坏情况
        // 是某个刚被 compute 换新的窗口又被删掉重建——限流语义不变，只是多算一轮新窗口。
        if (requests.incrementAndGet() % SWEEP_EVERY == 0) {
            clients.values().removeIf(w -> w.started().plus(window).isBefore(now));
        }
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
