package com.allen.questionbank.ops;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 自定义就绪探针：不仅看连接能否拿到，还看一次最轻查询的实际耗时。
 * liveness 决定是否重启，readiness 决定是否接流量；慢查询超过阈值时这里会 DOWN，
 * 让编排系统把实例摘掉而不是继续把流量打到已经拖慢的数据库上。
 *
 * <p>为什么探针只测 SELECT 1：探针的职责是回答"现在能不能接流量"（连接池可用 + 网络
 * 连通），不是业务健康度。业务查询延迟属于可观测性指标（Hikari micrometer 自动暴露），
 * 不该进 readiness 判断——把慢业务查询塞进探针，DB 一次抖动就会把所有实例同时摘除，
 * 反而放大故障。这是探针边界的设计选择，不是遗漏。
 */
@Component
public class DatabaseLatencyHealthIndicator implements HealthIndicator {

    private static final long SLOW_QUERY_THRESHOLD_MS = 500;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseLatencyHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        long startedNanos = System.nanoTime();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            Health.Builder builder = elapsedMillis > SLOW_QUERY_THRESHOLD_MS ? Health.down() : Health.up();
            return builder
                    .withDetail("probeQuery", "SELECT 1")
                    .withDetail("latencyMs", elapsedMillis)
                    .withDetail("thresholdMs", SLOW_QUERY_THRESHOLD_MS)
                    .build();
        } catch (RuntimeException failure) {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            return Health.down(failure)
                    .withDetail("latencyMs", elapsedMillis)
                    .build();
        }
    }
}
