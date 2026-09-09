package com.allen.questionbank;

import com.allen.questionbank.common.ReplicationRoutingDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 读写路由逻辑的快速证据（H2 双库模拟主从，CI 全环境跑）：
 * 拓扑是两个独立 H2 内存库——主库 probe 表为空、从库预置 sentinel 行，
 * 所以"读到什么"直接暴露路由到了哪个库，没有解释空间。
 */
@SpringBootTest(properties = {
        "app.replication.master-url=jdbc:h2:mem:routing_master;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.replication.slave-url=jdbc:h2:mem:routing_slave;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles({"test", "read-replica"})
class ReadReplicaRoutingH2Test {

    private static final String MASTER_URL =
            "jdbc:h2:mem:routing_master;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    private static final String SLAVE_URL =
            "jdbc:h2:mem:routing_slave;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeAll
    static void seedTwoSides(@Autowired JdbcTemplate bootstrappingTemplate) throws Exception {
        // 主库：表存在但没有 sentinel 行；从库：同结构 + sentinel 行
        try (Connection master = DriverManager.getConnection(MASTER_URL, "sa", "");
             Connection slave = DriverManager.getConnection(SLAVE_URL, "sa", "");
             Statement m = master.createStatement();
             Statement s = slave.createStatement()) {
            m.execute("CREATE TABLE IF NOT EXISTS replica_probe (id INT PRIMARY KEY, mark VARCHAR(40))");
            s.execute("CREATE TABLE IF NOT EXISTS replica_probe (id INT PRIMARY KEY, mark VARCHAR(40))");
            s.execute("MERGE INTO replica_probe KEY(id) VALUES (99, 'this-row-lives-only-on-slave')");
        }
        // 触发 Spring 上下文里真实使用一次（忽略结果），确保模板可用性由容器而非直连承担
        bootstrappingTemplate.queryForList("SELECT 1");
    }

    @Test
    void defaultContextAndWriteTransactionRouteToMaster() {
        Integer sentinelOnMaster = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replica_probe WHERE id = 99", Integer.class);
        assertEquals(0, sentinelOnMaster, "无事务默认路由主库：不该看到只存在于从库的 sentinel 行");

        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("INSERT INTO replica_probe (id, mark) VALUES (1, 'written-in-tx')"));
        Integer written = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM replica_probe WHERE id = 1", Integer.class);
        assertEquals(1, written, "写事务路由主库：插入立即可见（同一主库连接）");
    }

    @Test
    void readOnlyTransactionRoutesToSlave() {
        transactionTemplate.setReadOnly(true);
        Integer sentinel = transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM replica_probe WHERE id = 99", Integer.class));
        assertEquals(1, sentinel, "readOnly 事务必须路由从库：只有从库有 sentinel 行");

        Integer masterOnlyRow = transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM replica_probe WHERE id = 1", Integer.class));
        assertEquals(0, masterOnlyRow, "readOnly 事务看不到主库独有数据——路由确非主库");
    }

    @Test
    void routingLookupKeysAreDistinct() {
        assertNotEquals(ReplicationRoutingDataSource.WRITE, ReplicationRoutingDataSource.READ,
                "路由 key 必须可区分——写错 key 会让读写静默落同一库");
    }
}
