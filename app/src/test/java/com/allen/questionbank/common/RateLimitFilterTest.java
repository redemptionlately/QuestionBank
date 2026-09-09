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
}