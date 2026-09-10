package com.allen.questionbank;

import com.allen.questionbank.practice.WrongQuestionQueryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 MySQL 证据测试：默认关闭，设置环境变量 MYSQL_EVIDENCE=true 后启用。
 * 产出落在 output/ 的原始日志才是面试证据；这里只是可回归的断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "MYSQL_EVIDENCE", matches = "true")
class MysqlRealDbIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired WrongQuestionQueryMapper wrongQuestionQuery;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private static final String SEED_BANK = "SEED-BANK";
    // 测试自建账号：不依赖种子账号（admin/student 的密码是可变的用户数据），
    // 用注入的真实 PasswordEncoder 生成 BCrypt；@AfterEach 一并清理。2026-09-10 教训：
    // 测试曾硬编码 admin/admin123，用户改密码后 3306 单测 401。
    private static final String EV_ADMIN = "evidence-admin";
    private static final String EV_STUDENT = "evidence-student";
    private static final String EV_PASSWORD = "evidence-password-2026";

    @AfterEach
    void cleanupSeededRows() {
        // 顺序敏感：user_account 被 paper_version(created_by)/question_bank(owner_id)/
        // practice_session/wrong_question(student_id) 等 FK 引用，必须先清引用数据、最后删账号。
        // 不清理会在真实业务库留下残留（2026-09-10 教训：密码哈希 'x' 触发 BCrypt WARN；
        // 反向错误：先删账号撞 fk_paper_version_creator）。
        for (String u : new String[] {"wq-mybatis-evidence", EV_ADMIN, EV_STUDENT}) {
            jdbcTemplate.update("DELETE FROM wrong_question WHERE student_id IN "
                    + "(SELECT id FROM user_account WHERE username = ?)", u);
            jdbcTemplate.update("DELETE FROM submission_item WHERE session_id IN "
                    + "(SELECT id FROM practice_session WHERE student_id IN "
                    + "(SELECT id FROM user_account WHERE username = ?))", u);
            jdbcTemplate.update("DELETE FROM practice_session WHERE student_id IN "
                    + "(SELECT id FROM user_account WHERE username = ?)", u);
            // question_mastery 是 gitignored WIP 表（V5 迁移，本地存在、CI 无），
            // 守卫免表不存在时整个清理链挂掉（2026-09-10）
            Integer masteryTable = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = 'question_mastery'", Integer.class);
            if (masteryTable != null && masteryTable > 0) {
                jdbcTemplate.update("DELETE FROM question_mastery WHERE student_id IN "
                        + "(SELECT id FROM user_account WHERE username = ?)", u);
            }
        }

        List<Long> paperIds = jdbcTemplate.queryForList(
                "SELECT id FROM paper_version WHERE title LIKE 'SEED%'", Long.class);
        if (paperIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM question_bank WHERE name = ?", SEED_BANK);
        } else {
            String in = paperIds.stream().map(String::valueOf).collect(Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM wrong_question WHERE question_version_id IN "
                    + "(SELECT id FROM question_version WHERE paper_version_id IN (" + in + "))");
            jdbcTemplate.update("DELETE FROM submission_item WHERE session_id IN "
                    + "(SELECT id FROM practice_session WHERE paper_version_id IN (" + in + "))");
            jdbcTemplate.update("DELETE FROM practice_session WHERE paper_version_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM question_version WHERE paper_version_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM paper_version WHERE id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM question_bank WHERE name = ?", SEED_BANK);
        }

        // 最后删账号：此时 paper/bank/practice/wrong 引用均已清空
        for (String u : new String[] {"wq-mybatis-evidence", EV_ADMIN, EV_STUDENT}) {
            jdbcTemplate.update("DELETE FROM user_account WHERE username = ?", u);
        }
    }

    @Test
    void realMysqlRunsFullPublishAndIdempotentSubmitFlow() throws Exception {
        String admin = login(createEvidenceUser(EV_ADMIN, "ADMIN"), EV_PASSWORD);
        String student = login(createEvidenceUser(EV_STUDENT, "STUDENT"), EV_PASSWORD);

        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"SEED-BANK\",\"description\":\"real mysql evidence\"}"))
                .andExpect(status().isOk()).andReturn());

        JsonNode paper = json(mvc.perform(post("/api/admin/banks/{id}/versions", bank.get("id").asLong())
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                        .content("""
                                {"title":"SEED-REAL-MYSQL","questions":[
                                  {"prompt":"2+2?","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":5}
                                ]}"""))
                .andExpect(status().isOk()).andReturn());

        mvc.perform(post("/api/admin/versions/{id}/publish", paper.get("id").asLong())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", org.hamcrest.Matchers.is("PUBLISHED")));

        JsonNode detail = json(mvc.perform(get("/api/papers/{id}", paper.get("id").asLong())
                .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn());
        long questionId = detail.get("questions").get(0).get("id").asLong();

        JsonNode practice = json(mvc.perform(post("/api/practices")
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"paperVersionId\":" + paper.get("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        long sessionId = practice.get("id").asLong();

        mvc.perform(put("/api/practices/{session}/answers/{question}", sessionId, questionId)
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"answer\":[\"B\"]}"))
                .andExpect(status().isOk());

        MvcResult first = mvc.perform(post("/api/practices/{id}/submit", sessionId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "real-mysql-1"))
                .andExpect(status().isOk()).andReturn();
        MvcResult retry = mvc.perform(post("/api/practices/{id}/submit", sessionId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "real-mysql-1"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString(),
                "真实 MySQL 上同幂等键重放必须返回完全一致的结果快照");

        mvc.perform(get("/api/wrong-questions").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].wrongCount", org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void explainHitsCompositeIndexAndFunctionWrappedPredicateLosesIt() {
        long bankId = ensureSeedBank();
        int base = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM paper_version WHERE bank_id = ?", Integer.class, bankId);
        int total = 5000;
        int published = 300;

        jdbcTemplate.batchUpdate(
                "INSERT INTO paper_version (bank_id, version_no, title, status, created_by, created_at, published_at) "
                        + "VALUES (?,?,?,?,?,NOW(),?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        int versionNo = base + i + 1;
                        boolean isPublished = i < published;
                        ps.setLong(1, bankId);
                        ps.setInt(2, versionNo);
                        ps.setString(3, "SEED-EXPLAIN-" + versionNo);
                        ps.setString(4, isPublished ? "PUBLISHED" : "DRAFT");
                        ps.setLong(5, 1L);
                        if (isPublished) {
                            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now().minusSeconds(i * 7L)));
                        } else {
                            ps.setNull(6, Types.TIMESTAMP);
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return total;
                    }
                });
        jdbcTemplate.execute("ANALYZE TABLE paper_version");

        Map<String, Object> hit = explain(
                "EXPLAIN SELECT id, title FROM paper_version WHERE status='PUBLISHED' ORDER BY published_at DESC");
        Map<String, Object> miss = explain(
                "EXPLAIN SELECT id, title FROM paper_version WHERE UPPER(status)='PUBLISHED' ORDER BY published_at DESC");

        System.out.println("[evidence] EXPLAIN hit  = " + hit);
        System.out.println("[evidence] EXPLAIN miss = " + miss);

        assertEquals("idx_paper_version_status_published_at", hit.get("key"),
                "命中索引应当是 idx_paper_version_status_published_at，实际: " + hit);
        assertNull(miss.get("key"), "函数包裹索引列后应当索引失效(key 为 NULL)，实际: " + miss);
        assertEquals("ALL", miss.get("type"), "失效用例应为全表扫描(type=ALL)，实际: " + miss);
        assertTrue(extraOf(hit) == null || !extraOf(hit).toLowerCase(Locale.ROOT).contains("filesort"),
                "命中联合索引后 ORDER BY 不应出现 filesort，实际: " + hit);
    }

    @Test
    void repeatableReadKeepsSnapshotUntilCommit() throws Exception {
        long paperId = seedPaper("SEED-ISO-RR");
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            assertEquals("REPEATABLE-READ", isolationOf(a), "MySQL 9 默认隔离级别应为 REPEATABLE-READ");
            a.setAutoCommit(false);
            assertEquals("SEED-ISO-RR", titleOf(a, paperId), "RR 事务内首次读");

            b.setAutoCommit(false);
            updateTitle(b, paperId, "SEED-ISO-RR-UPDATED");
            b.commit();

            assertEquals("SEED-ISO-RR", titleOf(a, paperId),
                    "RR 下同一事务第二次读必须仍为事务开始时的快照值");
            a.commit();
            assertEquals("SEED-ISO-RR-UPDATED", titleOf(a, paperId), "提交后应读到新值");
        }
    }

    @Test
    void readCommittedSeesNewlyCommittedValue() throws Exception {
        long paperId = seedPaper("SEED-ISO-RC");
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            a.setAutoCommit(false);
            assertEquals("SEED-ISO-RC", titleOf(a, paperId), "RC 事务内首次读");

            b.setAutoCommit(false);
            updateTitle(b, paperId, "SEED-ISO-RC-UPDATED");
            b.commit();

            assertEquals("SEED-ISO-RC-UPDATED", titleOf(a, paperId),
                    "RC 下每次读都生成新快照，应看到他事务已提交的新值");
            a.commit();
        } finally {
            try (Connection reset = dataSource.getConnection()) {
                reset.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            }
        }
    }

    @Test
    void forUpdateRowLockBlocksSecondWriterUntilLockWaitTimeout() throws Exception {
        long paperId = seedPaper("SEED-LOCK");
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SET SESSION innodb_lock_wait_timeout = 2");
            lockRow(holder, paperId);

            Future<String> blocked = pool.submit(() -> {
                try (Connection other = dataSource.getConnection()) {
                    other.setAutoCommit(false);
                    other.createStatement().execute("SET SESSION innodb_lock_wait_timeout = 2");
                    lockRow(other, paperId);
                    other.commit();
                    return "ACQUIRED";
                } catch (SQLException e) {
                    return "SQLSTATE=" + e.getSQLState() + ", errorCode=" + e.getErrorCode()
                            + ", message=" + e.getMessage();
                }
            });

            String outcome = blocked.get(30, TimeUnit.SECONDS);
            System.out.println("[evidence] 第二写者结果 = " + outcome);
            assertTrue(outcome.contains("1205") || outcome.contains("Lock wait"),
                    "同一行的 FOR UPDATE 应当阻塞第二个事务直到锁等待超时，实际: " + outcome);
            holder.commit();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deadlockDetectedAndVictimRolledBack() throws Exception {
        long paperA = seedPaper("SEED-DEADLOCK-A");
        long paperB = seedPaper("SEED-DEADLOCK-B");
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            // 交叉加锁：A 持有行A 等行B；B 持有行B 等行A → 环路，InnoDB 死锁检测器立即介入
            lockRow(a, paperA);
            lockRow(b, paperB);

            Future<String> waitingOnB = pool.submit(() -> {
                lockRow(a, paperB); // 阻塞等待 B 释放行B
                return "ACQUIRED";
            });
            Thread.sleep(500); // 确保 A 已进入锁等待

            String victimOutcome;
            try {
                lockRow(b, paperA); // B 再要行A：成环
                victimOutcome = "NO-DEADLOCK-DETECTED";
                b.commit();
            } catch (SQLException e) {
                victimOutcome = "SQLSTATE=" + e.getSQLState() + ", errorCode=" + e.getErrorCode()
                        + ", message=" + e.getMessage();
            }

            String survivor = waitingOnB.get(30, TimeUnit.SECONDS);
            System.out.println("[evidence] 死锁牺牲者结果 = " + victimOutcome);
            System.out.println("[evidence] 幸存事务结果 = " + survivor);
            assertTrue(victimOutcome.contains("1213") || victimOutcome.equals("ACQUIRED"),
                    "死锁必须被 InnoDB 检测（errorCode 1213）或至少不让两边都拿到锁，实际: " + victimOutcome);
            a.commit();
        } finally {
            pool.shutdownNow();
        }
        printLatestDeadlockSection();
    }

    /** 从 SHOW ENGINE INNODB STATUS 抓 LATEST DETECTED DEADLOCK 段落，作为死锁的原始证据。 */
    private void printLatestDeadlockSection() {
        try {
            Map<String, Object> status = jdbcTemplate.queryForMap("SHOW ENGINE INNODB STATUS");
            String full = String.valueOf(status.get("Status"));
            int start = full.indexOf("LATEST DETECTED DEADLOCK");
            if (start < 0) {
                System.out.println("[evidence] INNODB STATUS 中没有 LATEST DETECTED DEADLOCK 段（可能被后续输出冲掉）");
                return;
            }
            // 段内本身就有分隔线，不能用"找下一条分隔线"定位结尾；固定窗口足够展示事务与锁信息
            String section = full.substring(start, Math.min(full.length(), start + 2500));
            System.out.println("[evidence] ---- LATEST DETECTED DEADLOCK ----\n" + section);
        } catch (Exception e) {
            System.out.println("[evidence] 读取 INNODB STATUS 失败（不影响断言）: " + e.getMessage());
        }
    }

    private void lockRow(Connection conn, long paperId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM paper_version WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, paperId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "FOR UPDATE 应锁定到目标行");
            }
        }
    }

    private String isolationOf(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT @@transaction_isolation");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String titleOf(Connection conn, long paperId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT title FROM paper_version WHERE id = ?")) {
            ps.setLong(1, paperId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void updateTitle(Connection conn, long paperId, String newTitle) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE paper_version SET title = ? WHERE id = ?")) {
            ps.setString(1, newTitle);
            ps.setLong(2, paperId);
            ps.executeUpdate();
        }
    }

    private long ensureSeedBank() {
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM question_bank WHERE name = ?", Long.class, SEED_BANK);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        jdbcTemplate.update("INSERT INTO question_bank (owner_id, name, description, status) VALUES (1, ?, ?, 'ACTIVE')",
                SEED_BANK, "seeded for evidence");
        List<Long> inserted = jdbcTemplate.queryForList(
                "SELECT id FROM question_bank WHERE name = ?", Long.class, SEED_BANK);
        if (inserted.isEmpty()) {
            throw new IllegalStateException("SEED-BANK 插入后仍查不到，检查 user_account.id=1 是否存在");
        }
        return inserted.get(0);
    }

    @Test
    void mybatisDynamicSqlAndAggregationAgreeWithHandWrittenSqlOnRealMysql() {
        // MyBatis 接入证据（真实 MySQL 9.0）：动态 SQL 的结果必须与手写 SQL 逐行一致。
        // 用独立用户而不是 student：同 JVM 内其他测试走 API 时会把 summarize(学生,null,null)
        // 的结果写进二级缓存，本测试再插行就会命中旧缓存——这是缓存弱一致性的正确规避方式，
        // 而不是让证据测试依赖执行顺序。
        List<Long> existingUser = jdbcTemplate.queryForList(
                "SELECT id FROM user_account WHERE username = 'wq-mybatis-evidence'", Long.class);
        Long userId = existingUser.isEmpty() ? null : existingUser.get(0);
        if (userId == null) {
            jdbcTemplate.update("INSERT INTO user_account (username, password_hash, role) "
                    + "VALUES ('wq-mybatis-evidence', 'x', 'STUDENT')");
            userId = jdbcTemplate.queryForObject(
                    "SELECT id FROM user_account WHERE username = 'wq-mybatis-evidence'", Long.class);
        }
        long paperId = seedPaper("SEED-mybatis-evidence");
        for (int no = 1; no <= 5; no++) {
            jdbcTemplate.update("INSERT INTO question_version (paper_version_id, question_no, prompt, question_type, options_json, answer_json, score) "
                    + "VALUES (?, ?, 'p', 'SINGLE', '[\"A\"]', '[\"A\"]', 5)", paperId, no);
        }
        List<Long> questionIds = jdbcTemplate.queryForList(
                "SELECT id FROM question_version WHERE paper_version_id = ? ORDER BY question_no", Long.class, paperId);
        Instant base = Instant.now();
        wrongQuestionRow(userId, questionIds.get(0), 1, base.minusSeconds(5400));
        wrongQuestionRow(userId, questionIds.get(1), 2, base.minusSeconds(2400));
        wrongQuestionRow(userId, questionIds.get(2), 4, base.minusSeconds(300));

        // 1) 聚合对照：MyBatis summarize vs 手写同义 SQL
        WrongQuestionQueryMapper.WrongQuestionSummary summary = wrongQuestionQuery.summarize(userId, null, null);
        Map<String, Object> manual = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS c, COALESCE(SUM(wrong_count),0) AS s, COALESCE(MAX(wrong_count),0) AS m "
                        + "FROM wrong_question WHERE student_id = ?", userId);
        assertEquals(((Number) manual.get("c")).longValue(), summary.getTotalQuestions(), "错题数与手写 SQL 不一致");
        assertEquals(((Number) manual.get("s")).longValue(), summary.getTotalWrongCount(), "累计错误次数与手写 SQL 不一致");
        assertEquals(((Number) manual.get("m")).intValue(), summary.getMaxWrongCount(), "单题最大错误次数与手写 SQL 不一致");

        // 2) 动态筛选 + 排序对照：wrongCountMin=2 → 与手写 SQL 的 id 序列完全一致
        List<Long> mybatisIds = wrongQuestionQuery.search(userId, 2, null, 50, 0).stream()
                .map(WrongQuestionQueryMapper.WrongQuestionRow::getQuestionVersionId).collect(Collectors.toList());
        List<Long> manualIds = jdbcTemplate.queryForList(
                "SELECT question_version_id FROM wrong_question WHERE student_id = ? AND wrong_count >= 2 "
                        + "ORDER BY last_wrong_at DESC, question_version_id DESC LIMIT 50 OFFSET 0", Long.class, userId);
        assertEquals(manualIds, mybatisIds, "MyBatis 动态 SQL 与手写 SQL 的结果序列必须逐行一致");
        assertEquals(2, mybatisIds.size());

        // 3) LIMIT/OFFSET 分页语义：size=1 page=1 取排序后的第二条
        List<Long> pageTwo = wrongQuestionQuery.search(userId, null, null, 1, 1).stream()
                .map(WrongQuestionQueryMapper.WrongQuestionRow::getQuestionVersionId).collect(Collectors.toList());
        assertEquals(manualIds.subList(1, 2), pageTwo);
    }

    private void wrongQuestionRow(long studentId, long questionVersionId, int count, Instant at) {
        jdbcTemplate.update("INSERT INTO wrong_question (student_id, question_version_id, wrong_count, last_wrong_at) VALUES (?,?,?,?)",
                studentId, questionVersionId, count, Timestamp.from(at));
    }

    private long seedPaper(String title) {
        long bankId = ensureSeedBank();
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM paper_version WHERE bank_id = ?", Integer.class, bankId);
        jdbcTemplate.update("INSERT INTO paper_version (bank_id, version_no, title, status, created_by) "
                + "VALUES (?,?,?, 'DRAFT', 1)", bankId, maxVersion + 1, title);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM paper_version WHERE title = ? ORDER BY id DESC LIMIT 1", Long.class, title);
    }

    private Map<String, Object> explain(String sql) {
        Map<String, Object> raw = jdbcTemplate.queryForMap(sql);
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((k, v) -> normalized.put(k.toLowerCase(Locale.ROOT), v));
        return normalized;
    }

    private String extraOf(Map<String, Object> plan) {
        Object extra = plan.get("extra");
        return extra == null ? null : String.valueOf(extra);
    }

    private String login(String username, String password) throws Exception {
        JsonNode response = json(mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return response.get("token").asText();
    }

    /** 幂等创建测试专属账号（true BCrypt 哈希），返回用户名。不依赖种子账号的可变密码。 */
    private String createEvidenceUser(String username, String role) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM user_account WHERE username = ?", Boolean.class, username));
        if (!exists) {
            jdbcTemplate.update("INSERT INTO user_account (username, password_hash, role) VALUES (?, ?, ?)",
                    username, passwordEncoder.encode(EV_PASSWORD), role);
        }
        return username;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
