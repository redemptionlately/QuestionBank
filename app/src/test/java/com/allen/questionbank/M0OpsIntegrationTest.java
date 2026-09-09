package com.allen.questionbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M0OpsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void containerProbesAreAccessibleWithoutToken() throws Exception {
        // CI 镜像冒烟抓出的真缺陷：SecurityConfig 曾只精确放行 /actuator/health，
        // readiness 子路径落到 authenticated() → 探针永远 401，容器 HEALTHCHECK 永远失败。
        // 探针（K8s liveness/readiness、Docker HEALTHCHECK）不带 token，必须无认证可达。
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void metricsEndpointExposesAtomicCountersForAuthenticatedUser() throws Exception {
        // 未登录访问 /api/metrics 应被 Security 拦截为 401
        mvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());

        JsonNode login = json(mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"username\":\"student\",\"password\":\"student123\"}"))
                .andExpect(status().isOk()).andReturn());

        mvc.perform(get("/api/metrics").header("Authorization", "Bearer " + login.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests").exists())
                .andExpect(jsonPath("$.failures").exists())
                .andExpect(jsonPath("$.totalLatencyNanos").exists());
    }

    @Test
    void swaggerDocsArePublicAndResponsesCarryRequestId() throws Exception {
        // 每个响应带 X-Request-Id（RequestIdFilter 已挂载）
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"username\":\"student\",\"password\":\"student123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));

        // OpenAPI 文档公开可达（无需 token）
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title", is("Question Bank M0 API")));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}