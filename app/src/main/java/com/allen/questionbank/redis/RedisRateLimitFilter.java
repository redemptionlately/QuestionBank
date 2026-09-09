package com.allen.questionbank.redis;

import com.allen.questionbank.common.RequestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 多实例共享的令牌桶限流（app.rate-limit.backend=redis 时启用，与本地固定窗口互斥）。
 *
 * 失败策略是 fail-open：Redis 故障时直接放行而不是拒绝全部流量——
 * 限流的目的是保护系统，不能在 Redis 挂掉时反而变成一次全站故障。
 * 代价是短暂失去限流保护，这个取舍必须显式声明而不是静默兜底。
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitFilter.class);

    private final RedisTokenBucketRateLimiter limiter;
    private final RequestMetrics metrics;
    private final int capacity;
    private final Duration window;

    public RedisRateLimitFilter(RedisTokenBucketRateLimiter limiter,
                                RequestMetrics metrics,
                                @Value("${app.rate-limit.capacity:300}") int capacity,
                                @Value("${app.rate-limit.window:PT1M}") Duration window) {
        this.limiter = limiter;
        this.metrics = metrics;
        this.capacity = Math.max(1, capacity);
        this.window = window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        metrics.request();
        String identity = request.getRemoteAddr() + ":" + request.getRequestURI();

        boolean allowed;
        try {
            allowed = limiter.tryAcquire(identity, capacity, window);
        } catch (RuntimeException redisFailure) {
            log.warn("限流依赖的 Redis 不可用，本次请求放行（fail-open）: {}", redisFailure.getMessage());
            allowed = true;
        }

        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, window.toSeconds())));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁\"}");
            metrics.failure();
            metrics.latency(System.nanoTime() - startedNanos);
            return;
        }

        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException | IOException error) {
            metrics.failure();
            throw error;
        } finally {
            metrics.latency(System.nanoTime() - startedNanos);
        }
    }
}
