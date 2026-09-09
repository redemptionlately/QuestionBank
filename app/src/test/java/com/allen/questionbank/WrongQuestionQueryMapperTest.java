package com.allen.questionbank;

import com.questionbank.monitor.MyBatisQueryMonitor;
import com.allen.questionbank.practice.WrongQuestionQueryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MyBatis 动态 SQL 的证据测试（H2，test profile）：
 * 动态筛选分支、分页语义、聚合正确性、二级缓存真实命中（用 StatementHandler 层计数器证明"第二次没打数据库"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WrongQuestionQueryMapperTest {

    private static final long STUDENT = 990_001L;
    private static final long QUESTION_BASE = 990_000L;

    @Autowired
    WrongQuestionQueryMapper mapper;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void seedReferenceChain(@Autowired JdbcTemplate jdbc) {
        // wrong_question 外键链 user → bank → paper → question：各建最小行（99xxxx 段 id 避开业务数据）
        jdbc.update("INSERT INTO user_account (id, username, password_hash, role) VALUES (990001, 'wq-mapper-test', 'x', 'STUDENT')");
        jdbc.update("INSERT INTO question_bank (id, owner_id, name) VALUES (990001, 990001, 'wq-mapper-test-bank')");
        jdbc.update("INSERT INTO paper_version (id, bank_id, version_no, title, created_by) VALUES (990001, 990001, 1, 'wq-mapper-test-paper', 990001)");
        for (int no = 1; no <= 10; no++) {
            jdbc.update("INSERT INTO question_version (id, paper_version_id, question_no, prompt, question_type, options_json, answer_json, score) "
                    + "VALUES (?, 990001, ?, 'p', 'SINGLE', '[\"A\"]', '[\"A\"]', 5)", QUESTION_BASE + no, no);
        }
    }

    @AfterEach
    void cleanupWrongRows() {
        jdbcTemplate.update("DELETE FROM wrong_question WHERE student_id = ?", STUDENT);
    }

    /** 四行基准数据（按 last_wrong_at DESC 固定为 q3 → q2 → q1 → q4）。 */
    private void seedBaselineRows() {
        Instant now = Instant.now();
        wrongRow(1, 1, now.minusSeconds(3600)); // q1
        wrongRow(2, 2, now.minusSeconds(2400)); // q2
        wrongRow(3, 3, now.minusSeconds(300));  // q3 最新、错得最多
        wrongRow(4, 1, now.minusSeconds(5400)); // q4 最旧
    }

    private void wrongRow(long questionOffset, int count, Instant at) {
        jdbcTemplate.update("INSERT INTO wrong_question (student_id, question_version_id, wrong_count, last_wrong_at) VALUES (?,?,?,?)",
                STUDENT, QUESTION_BASE + questionOffset, count, Timestamp.from(at));
    }

    private List<Long> questionIds(List<WrongQuestionQueryMapper.WrongQuestionRow> rows) {
        return rows.stream().map(WrongQuestionQueryMapper.WrongQuestionRow::getQuestionVersionId).collect(Collectors.toList());
    }

    @Test
    void noFilterReturnsAllRowsSortedByLastWrongAtDesc() {
        seedBaselineRows();
        List<Long> ids = questionIds(mapper.search(STUDENT, null, null, 50, 0));
        assertEquals(List.of(QUESTION_BASE + 3, QUESTION_BASE + 2, QUESTION_BASE + 1, QUESTION_BASE + 4), ids);
    }

    @Test
    void wrongCountMinFilterDropsBelowThreshold() {
        seedBaselineRows();
        List<Long> ids = questionIds(mapper.search(STUDENT, 2, null, 50, 0));
        assertEquals(List.of(QUESTION_BASE + 3, QUESTION_BASE + 2), ids);
    }

    @Test
    void wrongSinceFilterDropsOlderRows() {
        seedBaselineRows();
        Instant since = Instant.now().minusSeconds(3000); // 介于 q1(3600s) 与 q2(2400s) 之间：q1/q4 被排除
        List<Long> ids = questionIds(mapper.search(STUDENT, null, since, 50, 0));
        assertEquals(List.of(QUESTION_BASE + 3, QUESTION_BASE + 2), ids);
    }

    @Test
    void combinedFiltersNarrowTogether() {
        seedBaselineRows();
        // count>=2 且最近 15 分钟：只剩 q3（count=3、300s 前）
        List<Long> ids = questionIds(mapper.search(STUDENT, 2, Instant.now().minusSeconds(900), 50, 0));
        assertEquals(List.of(QUESTION_BASE + 3), ids);
    }

    @Test
    void paginationSlicesInSortedOrderAndBeyondOffsetReturnsEmpty() {
        seedBaselineRows();
        assertEquals(List.of(QUESTION_BASE + 3, QUESTION_BASE + 2), questionIds(mapper.search(STUDENT, null, null, 2, 0)));
        assertEquals(List.of(QUESTION_BASE + 1, QUESTION_BASE + 4), questionIds(mapper.search(STUDENT, null, null, 2, 2)));
        assertTrue(mapper.search(STUDENT, null, null, 2, 4).isEmpty(), "offset 越界应返回空页而不是报错");
    }

    @Test
    void summarizeMatchesManualAggregationWithAndWithoutFilter() {
        seedBaselineRows();
        WrongQuestionQueryMapper.WrongQuestionSummary all = mapper.summarize(STUDENT, null, null);
        assertEquals(4, all.getTotalQuestions());
        assertEquals(7, all.getTotalWrongCount());
        assertEquals(3, all.getMaxWrongCount());

        WrongQuestionQueryMapper.WrongQuestionSummary filtered = mapper.summarize(STUDENT, 2, null);
        assertEquals(2, filtered.getTotalQuestions());
        assertEquals(5, filtered.getTotalWrongCount());
        assertEquals(3, filtered.getMaxWrongCount());
    }

    @Test
    void secondIdenticalSummarizeHitsSecondLevelCacheWithoutSql() {
        // 独特参数保证缓存 key 不与其他测试撞：第一次必然 miss，第二次必须命中（不打数据库）
        int uniqueThreshold = 7777;
        MyBatisQueryMonitor.resetForTest();
        mapper.summarize(STUDENT, uniqueThreshold, null);
        long sqlAfterFirst = MyBatisQueryMonitor.executedSqlCount();
        assertTrue(sqlAfterFirst >= 1, "第一次查询必须真实执行 SQL");

        mapper.summarize(STUDENT, uniqueThreshold, null);
        long sqlAfterSecond = MyBatisQueryMonitor.executedSqlCount();
        assertEquals(sqlAfterFirst, sqlAfterSecond,
                "同参数第二次 summarize 必须命中二级缓存，StatementHandler 层计数不得增长");
    }

    @Test
    void apiRejectsIllegalPagingArguments() throws Exception {
        String token = login("student", "student123");
        mvc.perform(get("/api/wrong-questions?page=-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/wrong-questions?size=201").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int tokenStart = response.indexOf("\"token\":\"") + "\"token\":\"".length();
        return response.substring(tokenStart, response.indexOf('"', tokenStart));
    }
}
