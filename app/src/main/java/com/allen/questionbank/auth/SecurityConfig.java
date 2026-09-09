package com.allen.questionbank.auth;

import com.allen.questionbank.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;
import java.util.UUID;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    // BCrypt strength 可配（默认 10）：JFR profile 实锤 login 吞吐被 BCrypt 打满（占执行采样 84%），
    // strength 是"单次登录耗时（约 2^strength 次 key schedule）"与"在线暴力破解成本"的折中旋钮。
    // 改这个值必须配压测数据（scripts/bcrypt-strength-benchmark.sh），不许拍脑袋。
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:10}") int bcryptStrength) {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    UserDetailsService unusedFormLoginUserDetailsService() {
        return username -> { throw new UsernameNotFoundException(username); };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiTokenFilter tokenFilter,
                                            ObjectMapper objectMapper) throws Exception {
        // /actuator/prometheus 放行：Prometheus 抓取器没有用户态 token，
        // 生产环境的正确隔离手段是管理端口/网络策略，而不是应用层认证。
        // /actuator/health/**（含 readiness/liveness）放行：K8s 与容器探针不带 token，
        // CI 镜像冒烟实测 readiness 返回 401 导致 HEALTHCHECK 永远失败——探针路径必须无认证。
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/actuator/health/**", "/actuator/prometheus", "/error",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, 401, "AUTH_REQUIRED", "需要登录"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, objectMapper, 403, "FORBIDDEN", "没有执行该操作的权限")))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, ObjectMapper objectMapper,
                                   int status, String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(code, message, UUID.randomUUID().toString(), Instant.now()));
    }
}
