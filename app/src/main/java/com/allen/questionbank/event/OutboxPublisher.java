package com.allen.questionbank.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 事务内写入 outbox：调用方必须处于事务中，事件与业务变更要么一起提交、要么一起回滚。
 * 投递交给 {@link OutboxRelay}，本类绝不直接碰 Kafka——这是 Outbox 与"事务内发消息"的本质区别。
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class OutboxPublisher {

    /**
     * 写入用原生 SQL 而不是 JPA save，原因要在并发下才看得到：两个并发事务同时通过
     * existsBy 检查、同时 insert 同一事件时，uk_outbox_dedup 让后到者撞键。语义上
     * "事件已存在 = 静默跳过"，但 JPA 做不到——insert 延迟到 commit 才 flush，
     * DataIntegrityViolationException 在事务边界外才抛出来，方法内 catch 不到；
     * 改 saveAndFlush 能 catch，但 flush 失败后 Hibernate 会话按契约已不可信
     * （commit 会重放失败的 INSERT 或行为未定义），等于把业务事务一起搭进去。
     * {@code ON DUPLICATE KEY UPDATE id = id} 是单语句原子操作：撞 uk 时把主键更新为
     * 自身（无操作），语句成功返回、0 行受影响，业务事务继续提交。
     * 不用 INSERT IGNORE 是因为它把"数据超长"等其他错误也一并吞成 warning，掩盖问题。
     */
    static final String INSERT_SQL = """
            INSERT INTO outbox_event
                (aggregate_type, aggregate_id, event_type, payload_json, status, retry_count, created_at)
            VALUES (?, ?, ?, ?, ?, 0, ?)
            ON DUPLICATE KEY UPDATE id = id
            """;

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public OutboxPublisher(OutboxEventRepository repository, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /**
     * 同一聚合上的同一事件类型只入队一次（uk_outbox_dedup 兜底），
     * 避免同一事务被重放时写出重复事件。已存在时静默跳过而不是报错。
     * existsBy 只是廉价快路径（事件已存在时省掉序列化）；查与插之间存在竞态，
     * 并发正确性最终由 SQL 里的 ON DUPLICATE KEY 子句（即唯一键）裁决。
     */
    @Transactional
    public void enqueue(String aggregateType, Long aggregateId, String eventType, Object payload) {
        if (repository.existsByAggregateTypeAndAggregateIdAndEventType(aggregateType, aggregateId, eventType)) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception serializationFailure) {
            // 序列化失败说明事件本身有问题，必须让业务事务一起回滚，不能带着坏事件提交
            throw new IllegalStateException("outbox 事件序列化失败: " + eventType, serializationFailure);
        }
        // 校验放在 try 外：结果为空是"数据问题"，序列化失败是"代码问题"，
        // 混在 try 里会被上面的 catch 重新包装，两种失败路径就区分不开了
        if (json == null) {
            throw new IllegalStateException("outbox 事件序列化结果为空(null): " + eventType);
        }
        if (json.isBlank()) {
            throw new IllegalStateException("outbox 事件序列化结果为空(blank): " + eventType);
        }
        // JdbcTemplate 走的是当前事务的连接（JpaTransactionManager 会把 JDBC 连接暴露给
        // 同线程的 jdbc 操作）：事件与业务写入仍同事务提交/回滚，语义与 JPA save 一致
        jdbc.update(INSERT_SQL, aggregateType, aggregateId, eventType, json,
                OutboxEvent.STATUS_PENDING, Timestamp.from(Instant.now()));
    }
}
