package com.allen.questionbank.controller;

import com.allen.questionbank.common.RequestMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MetricsController {
    private final RequestMetrics metrics;
    public MetricsController(RequestMetrics metrics) { this.metrics = metrics; }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        return Map.of("requests", metrics.requests(), "failures", metrics.failures(),
                "totalLatencyNanos", metrics.totalLatencyNanos());
    }
}
