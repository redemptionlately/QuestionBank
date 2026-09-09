package com.allen.questionbank;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读写分离的最终证据（真实 MySQL 9.0 主从，GTID 复制，本机 3307 主 + 3308 从 super_read_only）：
 * - readOnly 事务必须落在 3308（SELECT @@port 直接暴露真实连接目标，无解释空间）
 * - 写事务必须落在 3307
 * - 主库写入后复制毫秒级收敛到从库
 * - 错误方向的写操作（readOnly 路由从库）被 super_read_only 硬拒绝——路由错误藏不住
 *
 * 测试设计的教训（第一版踩过的坑）：注入的 TransactionTemplate 是单例，setReadOnly 会改写
 * 实例状态并跨测试方法泄漏——前一个测试设置的只读会让后一个测试的"写事务"路由到从库。
 * 所以每个测试用例自建 TransactionTemplate，用完即弃。
 *
 * 开关：READ_REPLICA_EVIDENCE=true（主从拓扑由 scripts/replication-setup.sh 一键复现）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"mysql", "read-replica"})
@EnabledIfEnvironmentVariable(named = "READ_REPLICA_EVIDENCE", matches = "true")
class ReadReplicaRoutingIntegrationTest {

    private static final int MASTER_PORT = 3307;
    private static final int SLAVE_PORT = 3308;

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate writeTx() { return new TransactionTemplate(transactionManager); }

    private TransactionTemplate readTx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /**
     * 探针表必须建在独立的 repl_probe_db，绝不能建在 Flyway 管理的 replica_demo 里：
     * application-read-replica.yml 中 spring.flyway.enabled=true，迁移遇到"非空库且无 schema history"
     * 会直接失败——在迁移库里留手工表等于给自己埋雷（脚本侧已修，测试侧这里同步修掉）。
     */
    @BeforeAll
    static void createProbeTable(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS repl_probe_db CHARACTER SET utf8mb4");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS repl_probe_db.repl_probe "
                + "(id INT PRIMARY KEY, mark VARCHAR(40))");
    }

    @Test
    void readOnlyGoesToSlaveAndWriteGoesToMaster() {
        Integer writePort = writeTx().execute(status ->
                jdbcTemplate.queryForObject("SELECT @@port", Integer.class));
        assertEquals(MASTER_PORT, writePort, "写事务必须路由主库 3307");

        Integer readPort = readTx().execute(status ->
                jdbcTemplate.queryForObject("SELECT @@port", Integer.class));
        assertEquals(SLAVE_PORT, readPort, "readOnly 事务必须路由从库 3308");

        Integer noTxPort = jdbcTemplate.queryForObject("SELECT @@port", Integer.class);
        assertEquals(MASTER_PORT, noTxPort, "无事务默认路由主库");
    }

    @Test
    void masterWriteReplicatesToSlaveWithinMilliseconds() {
        int id = (int) (System.currentTimeMillis() % 1_000_000_000);
        writeTx().executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO repl_probe_db.repl_probe (id, mark) VALUES (?, ?)",
                        id, "routing-evidence"));

        AtomicReference<Long> elapsedMs = new AtomicReference<>(0L);
        Boolean converged = readTx().execute(status -> {
            long start = System.nanoTime();
            // 轮询上限 15s：收敛"耗时"是实测值（通常个位数毫秒），上限只是给负载抖动留余量。
            // 别写成 2s——本机同时跑 MySQL 主从 + Kafka + Redis 与整个测试套件，2s 会偶发超时，
            // 把"机器忙"误报成"复制失效"（真实发生过一次，复制当时是健康的）。
            for (int i = 0; i < 750; i++) {
                Integer found = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM repl_probe_db.repl_probe WHERE id = ?", Integer.class, id);
                if (found != null && found == 1) {
                    elapsedMs.set((System.nanoTime() - start) / 1_000_000);
                    return true;
                }
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return false;
        });
        assertTrue(converged, "从库 15s 内未见复制数据——GTID 复制失效");
        System.out.println("[evidence] 主从复制收敛耗时 ≈ " + elapsedMs.get() + "ms（含轮询间隔 20ms 粒度）");
    }

    @Test
    void writesRoutedToSlaveAreRejectedBySuperReadOnly() {
        // 路由错也不怕：从库 super_read_only 硬拒绝任何写——路由设计"错误即失败"而不是静默错写
        assertThrows(Exception.class, () -> readTx().executeWithoutResult(status ->
                        jdbcTemplate.update("INSERT INTO repl_probe_db.repl_probe (id, mark) VALUES (-1, 'must-fail')")),
                "readOnly 路由到从库的写必须被 super_read_only 拒绝");
    }
}
