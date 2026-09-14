# Day17 Spring Security · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `SecurityConfig`、`ApiTokenFilter`、`TokenService`、`AuthService`（app 模块，包 `com.allen.questionbank.auth`）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：[SecurityConfig.java:21-66](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java)（类级 `@Configuration`+`@EnableMethodSecurity` 在 :21-22；PasswordEncoder Bean :27-30；过滤链 securityFilterChain :37-57；放行清单 :47-49；entry point/access denied :50-54；addFilterBefore :55）；[ApiTokenFilter.java:16-39](../../app/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java)（@Component :16；doFilterInternal :22-36；内嵌 record AuthPrincipal :38）。

---

### Q2. 复现缺 token / 无效 token / 学生访问 admin。

| 场景 | 请求 | 过滤器/授权结果 | HTTP / code |
|---|---|---|---|
| 缺 token | 不带 Authorization 访问受保护接口 | ApiTokenFilter 不写 SecurityContext，anyRequest().authenticated()（SecurityConfig:49）拦截，authenticationEntryPoint 输出错误体 | 401 `AUTH_REQUIRED` |
| 无效 token | `Authorization: Bearer 不存在/过期` | `tokenService.resolve`（TokenService:27-34）返回 null（过期顺手 remove，:30），不写入 SecurityContext | 401 `AUTH_REQUIRED` |
| 格式错误 | `Authorization: abc`（无 `Bearer ` 前缀） | ApiTokenFilter:26 的 startsWith 不成立，当作未认证，继续 chain 交给授权层 | 401 `AUTH_REQUIRED` |
| 学生访问 admin | STUDENT 调 `/api/admin/**` | 已认证但方法级 `@PreAuthorize("hasRole('ADMIN')")` 拒绝，accessDeniedHandler（SecurityConfig:53-54）输出 | 403 `FORBIDDEN` |

---

### Q3. 写出 SecurityFilterChain 的 permitAll/authenticated/角色规则，并说明过滤器顺序。

真实骨架（SecurityConfig:37-57）：
```java
@Configuration
@EnableMethodSecurity                                  // :22，类级开启 @PreAuthorize
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${app.security.bcrypt-strength:10}") int bcryptStrength) { // :27-30，成本因子可配
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiTokenFilter tokenFilter,
                                            ObjectMapper objectMapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())      // 无状态 Bearer API，不用 CSRF token
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 不建 HttpSession
            .authorizeHttpRequests(a -> a
                // 放行清单（SecurityConfig:47-48），与基线逐字一致：
                .requestMatchers("/api/auth/login", "/actuator/health/**", "/actuator/prometheus",
                        "/error", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())         // :49 其余必须认证
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,ex) -> writeError(res, objectMapper, 401, "AUTH_REQUIRED", "需要登录"))
                .accessDeniedHandler((req,res,ex) -> writeError(res, objectMapper, 403, "FORBIDDEN", "没有执行该操作的权限")))
            .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class) // :55 token 过滤器插到前面
            .build();
    }
}
```
过滤器链顺序：请求 → Servlet 过滤器链（ApiTokenFilter 被 addFilterBefore 插在 UsernamePasswordAuthenticationFilter 之前，:55）→ 从 token 恢复认证写入 `SecurityContextHolder` → URL 级授权（authorizeHttpRequests）→ `@PreAuthorize` 方法级授权 → Controller。先认证（你是谁）后授权（你能干什么）。错误体由私有 writeError（:59-65）统一序列化为 `ErrorResponse(code,message,requestId,timestamp)` JSON。

> 放行清单的设计理由（源码注释 :40-43）：`/actuator/prometheus` 供 Prometheus 无 token 抓取（生产靠管理端口/网络策略隔离）；`/actuator/health/**`（含 readiness/liveness）供 K8s/容器探针使用，探针不带 token，返回 401 会让 HEALTHCHECK 永久失败；Swagger/OpenAPI 路径公开。

---

### Q4. 写出 BCrypt 编码、matches 校验和 Bearer 过滤器三分支。

```java
// SecurityConfig:27-30：强度由 app.security.bcrypt-strength 注入，默认 10（application.yml:40 显式 10）
String hash = passwordEncoder.encode("admin123");    // 每次编码带随机 salt，同一密码两次密文不同
// AuthService.login（:21-30，@Transactional(readOnly=true) 在 :21）
UserAccount user = users.findByUsername(username)
        .filter(UserAccount::isEnabled)
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "用户名或密码错误")); // :23-25
if (!passwordEncoder.matches(password, user.getPasswordHash())) {   // :26 恒定时间校验，不自行 equals
    throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "用户名或密码错误"); // :27 用户不存在与密码错误同文案，防账号枚举
}
```
Bearer 过滤器（ApiTokenFilter:22-36 真实代码）：
```java
String header = request.getHeader("Authorization");
if (header != null && header.startsWith("Bearer ")) {        // 分支①：格式正确（:26）
    TokenService.TokenRecord record = tokenService.resolve(header.substring(7)); // :27 截掉 "Bearer "
    if (record != null) {                                    // 分支②：token 存在且未过期（:28）
        var principal = new AuthPrincipal(record.userId(), record.username(), record.role()); // :29，record 定义 :38
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + record.role().name()))); // :30-31 hasRole 自动匹配 ROLE_ 前缀
        SecurityContextHolder.getContext().setAuthentication(auth);              // :32
    }                                                        // 分支③：未知/过期 token → record=null，保持匿名
}
chain.doFilter(request, response);                           // :35 无 header/格式错也放行到授权层，由其判 401
```
BCrypt 特点：随机 salt（防彩虹表）、成本因子 strength 可调（约 2^strength 次 key schedule，计算慢、抗暴力破解；本项目默认 10，调值必须配 [scripts/bcrypt-strength-benchmark.sh](../../scripts/bcrypt-strength-benchmark.sh) 压测）、不可逆，只校验不还原。

---

## External

### E1. 设计过期 / 撤销 / 刷新机制。
- **过期**：TokenRecord 带 `expiresAt`（TokenService:36），resolve 时发现过期惰性 remove 并返回 null（:29-31）；本项目 TTL 由 `app.token-ttl` 注入，默认 PT8H（TokenService:17，[application.yml:37](../../app/src/main/resources/application.yml)），test profile 为 PT1H（[application-test.yml:13](../../app/src/test/resources/application-test.yml)）；
- **撤销**：内存方案从 ConcurrentHashMap 删除即失效；生产用数据库/Redis 存 token 并加 `revoked` 标志，或用无状态 JWT + 服务端黑名单/短 TTL + refresh token；
- **刷新**：access token 短 TTL，另发 refresh token（更长 TTL、可轮换、可撤销），access 过期后用 refresh 换新，refresh 用一次即旋转（rotation）并检测重放盗用。
本项目是单实例教学用内存 token（TokenService:15 一个 ConcurrentHashMap），多实例需共享存储。注意 cloud/auth-service 走的是另一条路线：自研无状态 HMAC-SHA256 token（`com.allen.cloud.auth.TokenService`），与 app 不共享代码，不要混为一谈。

### E2. 检查日志不泄露 token。
- 日志只记录 token 指纹（如 SHA-256 前 8 位）或 `Bearer ***`，绝不打印完整 Authorization 头；
- 异常/访问日志脱敏请求体中的 password；错误响应不含 token、堆栈、SQL（GlobalExceptionHandler 兜底统一错误体）；
- `server.error.include-message: never`（[application.yml:35](../../app/src/main/resources/application.yml)）；登录失败统一文案“用户名或密码错误”（AuthService:25/:27），不回显“用户不存在”，避免账号枚举。
