# Day17 Spring Security · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `SecurityConfig`、`ApiTokenFilter`、`TokenService`、`AuthService`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`SecurityConfig` 22-45 行安全链与 entry point/access denied；`ApiTokenFilter` 23-38 行 Bearer 解析。

---

### Q2. 复现缺 token / 无效 token / 学生访问 admin。

| 场景 | 请求 | 过滤器/授权结果 | HTTP / code |
|---|---|---|---|
| 缺 token | 不带 Authorization 访问受保护接口 | 无认证信息，anyRequest().authenticated() 拦截 | 401 `AUTH_REQUIRED` |
| 无效 token | `Authorization: Bearer 不存在/过期` | `tokenService.resolve` 返回 null，不写入 SecurityContext | 401 `AUTH_REQUIRED` |
| 格式错误 | `Authorization: abc`（无 Bearer 前缀） | 不满足 startsWith("Bearer ")，当作未认证 | 401 `AUTH_REQUIRED` |
| 学生访问 admin | STUDENT 调 `/api/admin/**` | 已认证但 `@PreAuthorize("hasRole('ADMIN')")` 拒绝 | 403 `FORBIDDEN` |

---

### Q3. 写出 SecurityFilterChain 的 permitAll/authenticated/角色规则，并说明过滤器顺序。

真实骨架：
```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, ApiTokenFilter tokenFilter) throws Exception {
    http.csrf(csrf -> csrf.disable())                                   // 无状态 Bearer API，不用 CSRF token
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 不建 HttpSession
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/auth/login", "/actuator/health", "/error").permitAll() // 白名单
            .anyRequest().authenticated())                              // 其余必须认证
        .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class) // token 过滤器插到前面
        .exceptionHandling(e -> e
            .authenticationEntryPoint((req,res,ex) -> writeError(res,401,"AUTH_REQUIRED","需要登录"))
            .accessDeniedHandler((req,res,ex) -> writeError(res,403,"FORBIDDEN","无权访问")))
        .methodSecurity...; // @EnableMethodSecurity 开启 @PreAuthorize
    return http.build();
}
```
过滤器链顺序：请求 → 各 Servlet Filter（ApiTokenFilter 在 UsernamePasswordAuthenticationFilter 之前）→ 从 token 恢复认证写入 `SecurityContextHolder` → URL 级授权 → `@PreAuthorize` 方法级授权 → Controller。先认证（你是谁）后授权（你能干什么）。

---

### Q4. 写出 BCrypt 编码、matches 校验和 Bearer 过滤器三分支。

```java
// 注册/初始化：每次编码带随机 salt，同一密码两次密文不同
String hash = passwordEncoder.encode("admin123");
// 登录：用恒定时间比较，不自行 equals 密码
if (!passwordEncoder.matches(rawPassword, account.getPasswordHash()))
    throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "用户名或密码错误");
```
Bearer 过滤器（真实代码）：
```java
String header = request.getHeader("Authorization");
if (header != null && header.startsWith("Bearer ")) {        // 分支①：格式正确
    TokenService.TokenRecord record = tokenService.resolve(header.substring(7));
    if (record != null) {                                    // 分支②：token 存在且未过期
        var principal = new AuthPrincipal(record.userId(), record.username(), record.role());
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + record.role().name()))); // hasRole 自动匹配 ROLE_ 前缀
        SecurityContextHolder.getContext().setAuthentication(auth);
    }                                                        // 分支③：未知/过期 token → record=null，保持匿名
}
chain.doFilter(request, response);                           // 无 header/格式错也放行到授权层，由其判 401
```
BCrypt 特点：随机 salt（防彩虹表）、成本因子可调（计算慢，抗暴力破解）、不可逆，只校验不还原。

---

## External

### E1. 设计过期 / 撤销 / 刷新机制。
- **过期**：TokenRecord 带 `expiresAt`，resolve 时惰性删除并返回 null（本项目 TTL 默认 PT8H，测试 PT1H）；
- **撤销**：内存方案从 Map 删除即失效；生产用数据库/Redis 存 token 并加 `revoked` 标志，或用无状态 JWT + 服务端黑名单/短 TTL + refresh token；
- **刷新**：access token 短 TTL，另发 refresh token（更长 TTL、可轮换、可撤销），access 过期后用 refresh 换新，refresh 用一次即旋转（rotation）并检测重放盗用。
本项目是单实例教学用内存 token，多实例需共享存储（见 Day14 E2）。

### E2. 检查日志不泄露 token。
- 日志只记录 token 指纹（如 SHA-256 前 8 位）或 `Bearer ***`，绝不打印完整 Authorization 头；
- 异常/访问日志脱敏请求体中的 password；错误响应不含 token、堆栈、SQL；
- `server.error.include-message: never`；登录失败统一文案，不回显“用户不存在”，避免账号枚举。
