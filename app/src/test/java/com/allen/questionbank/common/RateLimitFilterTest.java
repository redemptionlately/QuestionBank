package com.allen.questionbank.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    @Test
    void rejectsRequestsBeyondFixedWindowCapacity() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RateLimitFilter filter = new RateLimitFilter(2, Duration.ofMinutes(1), metrics);

        // 前 2 次在容量内，正常放行
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request(), response, (req, res) -> {});
            assertEquals(200, response.getStatus(), "容量内请求应正常放行");
        }

        // 第 3 次超出容量，返回 429 + Retry-After
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilterInternal(request(), limited, (req, res) -> {});
        assertEquals(429, limited.getStatus());
        assertEquals("60", limited.getHeader("Retry-After"));
        assertTrue(limited.getContentAsString().contains("RATE_LIMITED"),
                "429 响应体应包含稳定错误码 RATE_LIMITED");

        // 指标：3 次请求、1 次失败
        assertEquals(3, metrics.requests());
        assertEquals(1, metrics.failures());
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/x");
    }

    @Test
    void allowedRequestActuallyReachesDownstreamChain() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RateLimitFilter filter = new RateLimitFilter(5, Duration.ofMinutes(1), metrics);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request(), response,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(234));

        assertEquals(234, response.getStatus(), "放行请求必须真实到达下游链——链路调用被删等于假放行");
        assertTrue(metrics.totalLatencyNanos() > 0, "放行路径必须记录延迟指标");
    }

    @Test
    void limitedResponseCarriesJsonContentTypeAndRecordsLatency() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RateLimitFilter filter = new RateLimitFilter(1, Duration.ofMinutes(1), metrics);

        filter.doFilterInternal(request(), new MockHttpServletResponse(), (req, res) -> { });
        long baselineNanos = metrics.totalLatencyNanos(); // 放行路径已累加，429 的延迟必须在此之上继续增长
        java.util.concurrent.atomic.AtomicInteger downstream = new java.util.concurrent.atomic.AtomicInteger();
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilterInternal(request(), limited, (req, res) -> downstream.incrementAndGet());

        assertEquals(429, limited.getStatus());
        assertEquals("application/json", limited.getContentType(), "429 响应必须是 JSON 契约");
        assertEquals(0, downstream.get(), "429 短路后不得再进下游链");
        assertTrue(metrics.totalLatencyNanos() > baselineNanos, "429 路径（独立于 finally）同样必须记录延迟");
    }

    @Test
    void zeroAndNegativeWindowsFallBackToOneMinute() throws Exception {
        RequestMetrics metrics = new RequestMetrics();

        RateLimitFilter zero = new RateLimitFilter(1, Duration.ZERO, metrics);
        zero.doFilterInternal(request(), new MockHttpServletResponse(), (req, res) -> { });
        MockHttpServletResponse second = new MockHttpServletResponse();
        zero.doFilterInternal(request(), second, (req, res) -> { });
        assertEquals(429, second.getStatus(), "零窗口必须回退到默认窗口，否则窗口恒过期等于永不限流");
        assertEquals("60", second.getHeader("Retry-After"), "回退后的 Retry-After 必须是 60s");

        RateLimitFilter negative = new RateLimitFilter(1, Duration.ofSeconds(-30), metrics);
        MockHttpServletResponse firstNeg = new MockHttpServletResponse();
        negative.doFilterInternal(request(), firstNeg, (req, res) -> { });
        MockHttpServletResponse secondNeg = new MockHttpServletResponse();
        negative.doFilterInternal(request(), secondNeg, (req, res) -> { });
        assertEquals(429, secondNeg.getStatus(), "负窗口同样必须回退到默认窗口");
        assertEquals("60", secondNeg.getHeader("Retry-After"));
    }
}