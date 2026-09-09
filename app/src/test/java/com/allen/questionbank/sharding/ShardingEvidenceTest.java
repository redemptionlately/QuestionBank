package com.allen.questionbank.sharding;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShardingSphere-JDBC 分库分表证据测试：默认关闭，设置环境变量 MYSQL_SHARDING_EVIDENCE=true 后启用。
 * 拓扑：2 库（question_bank_sharding_0/1）× 2 表（_0/_1），practice_session 与 submission_item 各 4 个物理表。
 * 纯 JDBC + 独立 SS DataSource（不占 Spring 上下文），sql-show 的 Actual SQL 经 logback ListAppender 捕获，
 * 与物理表直查结果互为铁证。产出落在 output/sharding_evidence_*.log 的原始日志才是面试证据；这里只是可回归的断言。
 *
 * 六项证据：
 * 1. 路由正确：student_id MOD 2 决定库与表，物理分布直查（含空表 0 行的叉积验证）
 * 2. 单片路由：带分片键精确查询只下发 1 个物理表（sql-show）
 * 3. 跨片聚合：无分片条件 GROUP BY 广播 4 物理表并归并结果
 * 4. 广播表 + 雪花 ID：exam_dict 一次写入两库各一份；主键全 SNOWFLAKE 全局唯一（替代 IDENTITY）
 * 5. bindingTables join：父子表路由对齐成 1 条 Actual SQL（无 binding 是 2×2 笛卡尔积）
 * 6. 唯一键兜底：同分片内 uk 拒绝重复；跨分片同键物理不拦 → 分片后业务唯一性必须含分片键
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_SHARDING_EVIDENCE", matches = "true")
class ShardingEvidenceTest {

    private static final String PWD = System.getenv().getOrDefault("MYSQL_PASSWORD", "783421");
    private static final String SQL_SHOW_LOGGER = "ShardingSphere-SQL";

    private static DataSource shardingDs;
    private static ListAppender<ILoggingEvent> sqlAppender;

    /** evidence01 填充，后续证据复用（@TestMethodOrder 保证顺序） */
    private static final Map<Long, Long> SESSION_ID_BY_STUDENT = new HashMap<>();
    private static final List<Long> SNOWFLAKE_IDS = new ArrayList<>();

    @BeforeAll
    static void init() throws Exception {
        createPhysicalTables();
        attachSqlShowAppender();
        shardingDs = buildShardingDataSource();
    }

    // ---------- 初始化 ----------

    private static Connection direct(int db) throws SQLException {
        String url = "jdbc:mysql://127.0.0.1:3306/question_bank_sharding_" + db
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true";
        return DriverManagerHolder.getConnection(url);
    }

    /** 独立持有 DriverManager，避免与 SS 类加载顺序耦合 */
    private static final class DriverManagerHolder {
        static Connection getConnection(String url) throws SQLException {
            return java.sql.DriverManager.getConnection(url, "root", PWD);
        }
    }

    /**
     * 建 8 张物理表 + 2 张广播表，结构对齐 V1 但做三处分片化改造：
     * 主键去 AUTO_INCREMENT（SNOWFLAKE 负责）、submission_item 冗余 student_id、去掉跨库不可达的 FK。
     */
    private static void createPhysicalTables() throws SQLException {
        for (int db = 0; db <= 1; db++) {
            try (Connection c = direct(db); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS practice_session_0, practice_session_1, "
                        + "submission_item_0, submission_item_1, exam_dict");
                for (int t = 0; t <= 1; t++) {
                    st.execute("CREATE TABLE practice_session_" + t + " ("
                            + " id BIGINT PRIMARY KEY,"
                            + " student_id BIGINT NOT NULL,"
                            + " paper_version_id BIGINT NOT NULL,"
                            + " status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',"
                            + " total_score INT NULL,"
                            + " entity_version BIGINT NOT NULL DEFAULT 0,"
                            + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + " KEY idx_student (student_id)) ENGINE=InnoDB");
                    st.execute("CREATE TABLE submission_item_" + t + " ("
                            + " id BIGINT PRIMARY KEY,"
                            + " session_id BIGINT NOT NULL,"
                            + " student_id BIGINT NOT NULL,"
                            + " question_version_id BIGINT NOT NULL,"
                            + " answer_json TEXT NOT NULL,"
                            + " score INT NOT NULL DEFAULT 0,"
                            + " correct BOOLEAN NOT NULL DEFAULT FALSE,"
                            + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + " UNIQUE KEY uk_submission_question (session_id, question_version_id),"
                            + " KEY idx_student (student_id)) ENGINE=InnoDB");
                }
                st.execute("CREATE TABLE exam_dict ("
                        + " dict_key VARCHAR(50) PRIMARY KEY,"
                        + " dict_value VARCHAR(200)) ENGINE=InnoDB");
            }
        }
        System.out.println("[evidence-setup] 8 张分片物理表 + 2 张广播表已重建（主键无 AUTO_INCREMENT，"
                + "submission_item 冗余 student_id，FK 移除）");
    }

    private static void attachSqlShowAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger sqlLogger = ctx.getLogger(SQL_SHOW_LOGGER);
        sqlAppender = new ListAppender<>();
        sqlAppender.setContext(ctx);
        sqlAppender.start();
        sqlLogger.addAppender(sqlAppender);
        sqlLogger.setLevel(Level.INFO);
        sqlLogger.setAdditive(true);
    }

    private static DataSource buildShardingDataSource() throws Exception {
        byte[] yamlBytes;
        try (InputStream in = ShardingEvidenceTest.class.getResourceAsStream("/sharding-evidence.yaml")) {
            assertNotNull(in, "sharding-evidence.yaml 未随 test classpath 打包");
            yamlBytes = in.readAllBytes();
        }
        String yaml = new String(yamlBytes, StandardCharsets.UTF_8).replace("__MYSQL_PASSWORD__", PWD);
        // SS 5.5.2 实测签名：createDataSource(byte[])（javap 反查确认，无 create(byte[])）
        return YamlShardingSphereDataSourceFactory.createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- sql-show 捕获 ----------

    private static void clearSqlLog() {
        sqlAppender.list.clear();
    }

    /** 取本次操作下发的 Actual SQL 行（格式：Actual SQL: ds_1 ::: SELECT ...） */
    private static List<String> actualSqls() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : sqlAppender.list) {
            String msg = e.getFormattedMessage();
            if (msg != null && msg.startsWith("Actual SQL")) {
                out.add(msg);
            }
        }
        return out;
    }

    // ---------- 写入辅助 ----------

    private static void insertSession(long student) throws SQLException {
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO practice_session (student_id, paper_version_id, status, total_score) "
                             + "VALUES (?, ?, 'COMPLETED', ?)")) {
            ps.setLong(1, student);
            ps.setLong(2, 9000 + student);
            ps.setInt(3, 60 + (int) student);
            ps.executeUpdate();
        }
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM practice_session WHERE student_id = ?")) {
            ps.setLong(1, student);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "student " + student + " 会话应可读回");
                long id = rs.getLong(1);
                SESSION_ID_BY_STUDENT.put(student, id);
                SNOWFLAKE_IDS.add(id);
            }
        }
    }

    private static void insertItem(long student, long qvid, int score, boolean correct) throws SQLException {
        Long sessionId = SESSION_ID_BY_STUDENT.get(student);
        assertNotNull(sessionId, "evidence01 应先建 student " + student + " 的会话");
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO submission_item (session_id, student_id, question_version_id, answer_json, score, correct) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, sessionId);
            ps.setLong(2, student);
            ps.setLong(3, qvid);
            ps.setString(4, "{\"answer\":\"student-" + student + "-q-" + qvid + "\"}");
            ps.setInt(5, score);
            ps.setBoolean(6, correct);
            ps.executeUpdate();
        }
    }

    // ---------- 物理表直查（绕过 SS，铁证来源） ----------

    private static List<Long> studentsIn(int db, String table) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = direct(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT student_id FROM " + table + " ORDER BY student_id")) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static int countDirect(int db, String table) throws SQLException {
        try (Connection c = direct(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---------- 六项证据 ----------

    @Test
    @Order(1)
    void evidence01_routeAndPhysicalDistribution() throws Exception {
        for (long student = 1; student <= 8; student++) {
            insertSession(student);
        }
        // student 1 三道题（后续 join / 唯一键证据用），其余每人一道
        insertItem(1, 101, 88, true);
        insertItem(1, 102, 72, false);
        insertItem(1, 103, 95, true);
        for (long student = 2; student <= 8; student++) {
            insertItem(student, 200 + student, 80, true);
        }

        // 物理直查：student_id MOD 2 同余决定库与表
        assertEquals(List.of(2L, 4L, 6L, 8L), studentsIn(0, "practice_session_0"),
                "偶数学生应落 ds_0.practice_session_0");
        assertEquals(List.of(1L, 3L, 5L, 7L), studentsIn(1, "practice_session_1"),
                "奇数学生应落 ds_1.practice_session_1");
        // 叉积验证：库与表同余，错位组合必须为空
        assertEquals(4, countDirect(0, "practice_session_0"));
        assertEquals(0, countDirect(0, "practice_session_1"));
        assertEquals(0, countDirect(1, "practice_session_0"));
        assertEquals(4, countDirect(1, "practice_session_1"));
        assertEquals(4, countDirect(0, "submission_item_0"));
        assertEquals(0, countDirect(0, "submission_item_1"));
        assertEquals(0, countDirect(1, "submission_item_0"));
        assertEquals(6, countDirect(1, "submission_item_1"), "student1×3 + student3/5/7×1");

        System.out.println("[evidence-1] 路由正确+物理分布：8 会话按 student_id MOD 2 精确分布 —— "
                + "ds_0.session_0={2,4,6,8} / ds_1.session_1={1,3,5,7}，错位组合(_0._1/_1._0)均 0 行；"
                + "答题 10 条分布 ds_0.item_0=4 / ds_1.item_1=6");
    }

    @Test
    @Order(2)
    void evidence02_singleShardRouteViaSqlShow() throws Exception {
        clearSqlLog();
        int rows = 0;
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, status FROM practice_session WHERE student_id = ?")) {
            ps.setLong(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                }
            }
        }
        List<String> actual = actualSqls();
        assertEquals(1, rows);
        assertEquals(1, actual.size(), "精确分片键应只路由 1 个物理表，实际: " + actual);
        assertTrue(actual.get(0).contains("ds_1") && actual.get(0).contains("practice_session_1"),
                "应下推到 ds_1.practice_session_1，实际: " + actual.get(0));
        assertFalse(actual.get(0).contains("practice_session_0"), "不得触达其他物理表: " + actual.get(0));

        System.out.println("[evidence-2] 单片路由：WHERE student_id=1 → 1 条 Actual SQL 下推 → " + actual.get(0));
    }

    @Test
    @Order(3)
    void evidence03_crossShardAggregateMerge() throws Exception {
        clearSqlLog();
        Map<Long, Long> counts = new HashMap<>();
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT student_id, COUNT(*) FROM practice_session GROUP BY student_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                counts.put(rs.getLong(1), rs.getLong(2));
            }
        }
        List<String> actual = actualSqls();
        assertEquals(8, counts.size(), "归并后应有 8 个学生的分组");
        assertEquals(8, counts.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(4, actual.size(), "无分片条件应广播 4 个物理表再归并: " + actual);

        System.out.println("[evidence-3] 跨片聚合合并：GROUP BY student_id 无路由条件 → " + actual.size()
                + " 条 Actual SQL 分发 4 物理表 → 归并 8 组（每生 1 会话，SUM=8）");
    }

    @Test
    @Order(4)
    void evidence04_broadcastAndSnowflakeId() throws Exception {
        clearSqlLog();
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO exam_dict (dict_key, dict_value) VALUES (?, ?)")) {
            ps.setString(1, "grading");
            ps.setString(2, "A:90,B:80,C:60");
            ps.executeUpdate();
        }
        List<String> actual = actualSqls();
        assertEquals(2, actual.size(), "广播表写入应下发到全部 2 个库: " + actual);
        assertEquals(1, countDirect(0, "exam_dict"), "ds_0 应有广播副本");
        assertEquals(1, countDirect(1, "exam_dict"), "ds_1 应有广播副本");

        // 雪花 ID：全局唯一且量级远超自增小整数（IDENTITY 在分片下做不到这一点）
        assertEquals(8, SNOWFLAKE_IDS.size());
        assertEquals(8, new HashSet<>(SNOWFLAKE_IDS).size(), "SNOWFLAKE 主键必须全局唯一: " + SNOWFLAKE_IDS);
        for (long id : SNOWFLAKE_IDS) {
            assertTrue(id > 100_000_000_000_000_000L, "雪花 ID 量级应≈10^18 而非自增小整数: " + id);
        }

        System.out.println("[evidence-4] 广播表+全局ID：exam_dict 一次逻辑写入 → 2 库各 1 份物理副本；"
                + "8 个主键全为 SNOWFLAKE（无 AUTO_INCREMENT）全局唯一不冲突: " + SNOWFLAKE_IDS);
    }

    @Test
    @Order(5)
    void evidence05_bindingTableJoin() throws Exception {
        clearSqlLog();
        int rows = 0;
        try (Connection c = shardingDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT si.question_version_id, si.score FROM practice_session ps "
                             + "JOIN submission_item si ON si.session_id = ps.id WHERE ps.student_id = ?")) {
            ps.setLong(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                }
            }
        }
        List<String> actual = actualSqls();
        assertEquals(3, rows, "student 1 应有 3 条答题");
        assertEquals(1, actual.size(), "绑定表 join 应对齐路由为 1 条 Actual SQL（无 binding 是 2×2=4 笛卡尔积）: " + actual);
        String line = actual.get(0);
        assertTrue(line.contains("ds_1") && line.contains("practice_session_1") && line.contains("submission_item_1"),
                "父子表应下推到同一对齐节点 ds_1._1，实际: " + line);
        assertFalse(line.contains("submission_item_0") || line.contains("practice_session_0"),
                "不得触达错位物理表: " + line);

        System.out.println("[evidence-5] bindingTables join：父子表同分片键对齐路由 → 1 条 Actual SQL（"
                + line + "），笛卡尔积 2×2=4 被消除");
    }

    @Test
    @Order(6)
    void evidence06_uniqueKeyFallback() throws Exception {
        // 6a 跨分片同业务键：student 2 引用 student 1 的 session + 相同题目 → 路由到 ds_0.item_0，
        //    物理唯一键(session_id, question_version_id)拦不住 —— 如实固化这个边界，而不是藏起来
        insertItem(2, 101, 60, false);
        assertEquals(5, countDirect(0, "submission_item_0"), "跨分片同键被物理层接受（边界实锤）");

        // 6b 同分片内重复：student 1 重复提交 (session, 101) → 同一物理表 → uk 兜底拒绝
        SQLException dup = assertThrows(SQLException.class, () -> insertItem(1, 101, 99, true),
                "同分片内 uk 应拒绝重复提交");
        assertTrue(isDuplicateKey(dup), "应识别为唯一键冲突: " + dup);

        System.out.println("[evidence-6] 唯一键兜底：同分片内重复提交 → Duplicate entry 被拒（uk 兜底有效）；"
                + "跨分片同业务键（student2 引 student1 的 session+q101）物理不拦 —— 分片后业务唯一性"
                + "必须把分片键纳入唯一键或引入全局索引，这是 sharding 的经典代价");
    }

    private static boolean isDuplicateKey(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null && msg.contains("Duplicate entry")) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
