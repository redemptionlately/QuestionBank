package com.allen.questionbank.bank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Non-choice types end to end (H2, no shared DB Scholar): FILL_BLANK order
 * sensitive grading, SHORT_ANSWER whitespace tolerant grading, validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 独立内存库：H2 mem 默认 DB_CLOSE_DELAY=-1 常驻 JVM，用 DirtiesContext 关上下文也清不掉数据，
// 会污染共享库的精确计数断言（如 M0Flow 错题本条数），故直接换库名彻底隔离。
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:qb_nonchoice;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class NonChoiceFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private String login(String user, String pass) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void fillBlankOrderSensitiveAndShortTolerant() throws Exception {
        String admin = login("admin", "admin123");
        String student = login("student", "student123");

        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"NC Bank\",\"description\":\"non-choice\"}"))
                .andExpect(status().isOk()).andReturn());

        String paperBody = """
                {"title":"NC Paper","questions":[
                  {"prompt":"sort __ and __","type":"FILL_BLANK","options":[],"correctAnswers":["compare","move"],"score":5},
                  {"prompt":"state the idea","type":"SHORT_ANSWER","options":[],"correctAnswers":["reference text"],"score":5}
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
                .andExpect(status().isOk()).andReturn());
        long fillId = detail.get("questions").get(0).get("id").asLong();
        long shortId = detail.get("questions").get(1).get("id").asLong();

        // session 1: reversed blanks -> wrong; padded short -> right
        JsonNode s1 = json(mvc.perform(post("/api/practices")
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"paperVersionId\":" + paper.get("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(put("/api/practices/{session}/answers/{question}", s1.get("id").asLong(), fillId)
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"answer\":[\"move\",\"compare\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/practices/{session}/answers/{question}", s1.get("id").asLong(), shortId)
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"answer\":[\"  reference   text \"]}"))
                .andExpect(status().isOk());
        JsonNode r1 = json(mvc.perform(post("/api/practices/{id}/submit", s1.get("id").asLong())
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "nc-1"))
                .andExpect(status().isOk()).andReturn());
        assertGrade(r1, fillId, false);
        assertGrade(r1, shortId, true);

        // session 2: correct order -> right
        JsonNode s2 = json(mvc.perform(post("/api/practices")
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"paperVersionId\":" + paper.get("id").asLong() + "}"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(put("/api/practices/{session}/answers/{question}", s2.get("id").asLong(), fillId)
                        .header("Authorization", bearer(student)).contentType(APPLICATION_JSON)
                        .content("{\"answer\":[\"compare\",\"move\"]}"))
                .andExpect(status().isOk());
        JsonNode r2 = json(mvc.perform(post("/api/practices/{id}/submit", s2.get("id").asLong())
                        .header("Authorization", bearer(student)).header("Idempotency-Key", "nc-2"))
                .andExpect(status().isOk()).andReturn());
        assertGrade(r2, fillId, true);
    }

    private void assertGrade(JsonNode result, long questionId, boolean expected) {
        for (JsonNode g : result.get("answers")) {
            if (g.get("questionId").asLong() == questionId) {
                if (g.get("correct").asBoolean() != expected) {
                    throw new AssertionError("grade mismatch q=" + questionId + " expected=" + expected);
                }
                return;
            }
        }
        throw new AssertionError("grade missing q=" + questionId);
    }

    @Test
    void singleStillRequiresOptions() throws Exception {
        String admin = login("admin", "admin123");
        JsonNode bank = json(mvc.perform(post("/api/admin/banks")
                        .header("Authorization", bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"NC Bank 2\",\"description\":\"x\"}"))
                .andExpect(status().isOk()).andReturn());
        String bad = """
                {"title":"Bad Paper","questions":[
                  {"prompt":"q?","type":"SINGLE","options":[],"correctAnswers":["A"],"score":5}
                ]}
                """;
        mvc.perform(post("/api/admin/banks/{id}/versions", bank.get("id").asLong())
                        .header("Authorization", bearer(admin)).contentType(APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }
}
