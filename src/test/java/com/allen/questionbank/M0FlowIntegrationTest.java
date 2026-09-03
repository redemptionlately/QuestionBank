package com.allen.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.allen.questionbank.bank.PaperVersionRepository;
import com.allen.questionbank.bank.QuestionVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;
import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M0FlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PaperVersionRepository papers;
    @Autowired QuestionVersionRepository questions;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void adminPublishesAndStudentSubmitsIdempotently() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("student", "student123");

        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                .header("Authorization", bearer(admin))
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"M0 Bank\",\"description\":\"integration\"}"))
                .andExpect(status().isOk()).andReturn());

        String paperBody = """
                {"title":"M0 Paper","questions":[
                  {"prompt":"2+2?","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":5,"explanation":"basic"}
                ]}
                """;
        JsonNode paper = json(mvc.perform(post("/api/admin/banks/{id}/versions", bank.get("id").asLong())
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON).content(paperBody))
                .andExpect(status().isOk()).andReturn());

        mvc.perform(post("/api/admin/versions/{id}/publish", paper.get("id").asLong())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PUBLISHED")));

        JsonNode detail = json(mvc.perform(get("/api/papers/{id}", paper.get("id").asLong())
                .header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questions", hasSize(1))).andReturn());
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
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "submit-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalScore", is(0))).andReturn();
        MvcResult retry = mvc.perform(post("/api/practices/{id}/submit", sessionId)
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "submit-1"))
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());

        mvc.perform(get("/api/wrong-questions").header("Authorization", bearer(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].wrongCount", is(1)));
    }

    @Test
    void rolesAndValidationAreEnforced() throws Exception {
        String student = login("student", "student123");
        mvc.perform(post("/api/admin/banks").header("Authorization", bearer(student))
                        .contentType(APPLICATION_JSON).content("{\"name\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("AUTH_INVALID")));
    }

    @Test
    void gradesAllQuestionTypesAndLocksSubmittedPractice() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("student", "student123");
        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Typed Bank\"}"))
                .andExpect(status().isOk()).andReturn());
        String body = """
                {"title":"Typed Paper","questions":[
                  {"prompt":"single","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":2},
                  {"prompt":"multiple","type":"MULTIPLE","options":["A","B","C"],"correctAnswers":["A","C"],"score":5},
                  {"prompt":"boolean","type":"TRUE_FALSE","options":["TRUE","FALSE"],"correctAnswers":["TRUE"],"score":3}
                ]}
                """;
        JsonNode paper = json(mvc.perform(post("/api/admin/banks/{id}/versions", bank.get("id").asLong())
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/admin/versions/{id}/publish", paper.get("id").asLong())
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        JsonNode detail = json(mvc.perform(get("/api/papers/{id}", paper.get("id").asLong())
                .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn());
        long q1 = detail.get("questions").get(0).get("id").asLong();
        long q2 = detail.get("questions").get(1).get("id").asLong();
        long q3 = detail.get("questions").get(2).get("id").asLong();
        JsonNode practice = json(mvc.perform(post("/api/practices").header("Authorization", bearer(student))
                .contentType(APPLICATION_JSON).content("{\"paperVersionId\":" + paper.get("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        long session = practice.get("id").asLong();
        save(mvc, student, session, q1, "[\"A\"]");
        save(mvc, student, session, q2, "[\"C\",\"A\"]");
        save(mvc, student, session, q3, "[\"TRUE\"]");
        mvc.perform(post("/api/practices/{id}/submit", session).header("Authorization", bearer(student))
                .header("Idempotency-Key", "typed-submit")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalScore", is(10))).andExpect(jsonPath("$.maxScore", is(10)))
                .andExpect(jsonPath("$.answers", hasSize(3)));
        mvc.perform(put("/api/practices/{session}/answers/{question}", session, q1)
                .header("Authorization", bearer(student)).contentType(APPLICATION_JSON).content("{\"answer\":[\"B\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedSubmitRequestIsBadRequestJson() throws Exception {
        String student = login("student", "student123");
        mvc.perform(post("/api/practices/999999/submit").header("Authorization", bearer(student)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REQUEST_INVALID")));
        mvc.perform(get("/api/papers/published").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("AUTH_REQUIRED")));
    }

    @Test
    void invalidQuestionRollsBackTheWholeDraftTransaction() throws Exception {
        String admin = login("admin", "admin123");
        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Rollback Bank\"}"))
                .andExpect(status().isOk()).andReturn());
        long bankId = bank.get("id").asLong();
        int papersBefore = (int) papers.count();
        int questionsBefore = (int) questions.count();

        String invalidDraft = """
                {"title":"Should Roll Back","questions":[
                  {"prompt":"valid first","type":"SINGLE","options":["A","B"],"correctAnswers":["A"],"score":1},
                  {"prompt":"invalid second","type":"SINGLE","options":["A","B"],"correctAnswers":["A","B"],"score":1}
                ]}
                """;
        mvc.perform(post("/api/admin/banks/{id}/versions", bankId)
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON).content(invalidDraft))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(papersBefore, papers.count());
        org.junit.jupiter.api.Assertions.assertEquals(questionsBefore, questions.count());
    }

    @Test
    void concurrentSubmitWithSameKeyReturnsOneStableResult() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("student", "student123");
        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"Concurrent Bank\"}"))
                .andExpect(status().isOk()).andReturn());
        JsonNode paper = json(mvc.perform(post("/api/admin/banks/{id}/versions", bank.get("id").asLong())
                .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON)
                .content("{\"title\":\"Concurrent Paper\",\"questions\":[{\"prompt\":\"q\",\"type\":\"SINGLE\",\"options\":[\"A\",\"B\"],\"correctAnswers\":[\"A\"],\"score\":1}] }"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/admin/versions/{id}/publish", paper.get("id").asLong())
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        JsonNode practice = json(mvc.perform(post("/api/practices")
                .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                .content("{\"paperVersionId\":" + paper.get("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        long sessionId = practice.get("id").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> concurrentSubmit(student, sessionId, ready, start));
            Future<String> second = pool.submit(() -> concurrentSubmit(student, sessionId, ready, start));
            ready.await();
            start.countDown();
            String firstBody = first.get();
            String secondBody = second.get();
            org.junit.jupiter.api.Assertions.assertEquals(firstBody, secondBody);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queryIndexesExistForPublishedAndStudentReadPaths() {
        org.junit.jupiter.api.Assertions.assertEquals(1, indexCount("idx_paper_version_status_published_at"));
        org.junit.jupiter.api.Assertions.assertEquals(1, indexCount("idx_practice_session_student_created_at"));
        org.junit.jupiter.api.Assertions.assertEquals(1, indexCount("idx_wrong_question_student_last_wrong_at"));
    }

    @Test
    void importJobIsAcceptedAndCompletesAsynchronously() throws Exception {
        String student = login("student", "student123");
        JsonNode created = json(mvc.perform(post("/api/import-jobs")
                .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                .content("{\"sourceName\":\"sample.pdf\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/import-jobs/")))
                .andExpect(jsonPath("$.status", is("RECEIVED"))).andReturn());
        long id = created.get("id").asLong();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        JsonNode current;
        do {
            current = json(mvc.perform(get("/api/import-jobs/{id}", id)
                    .header("Authorization", bearer(student))).andExpect(status().isOk()).andReturn());
            if ("SUCCEEDED".equals(current.get("status").asText())) break;
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", current.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(100, current.get("progress").asInt());
    }

    private int indexCount(String indexName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                + "WHERE LOWER(INDEX_NAME) = LOWER(?)", Integer.class, indexName);
    }

    private String concurrentSubmit(String token, long sessionId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        return mvc.perform(post("/api/practices/{id}/submit", sessionId)
                        .header("Authorization", bearer(token)).header("Idempotency-Key", "concurrent-submit"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void save(MockMvc mvc, String token, long session, long question, String answer) throws Exception {
        mvc.perform(put("/api/practices/{session}/answers/{question}", session, question)
                        .header("Authorization", bearer(token)).contentType(APPLICATION_JSON)
                        .content("{\"answer\":" + answer + "}"))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        JsonNode response = json(mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return response.get("token").asText();
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private String bearer(String token) { return "Bearer " + token; }
}
