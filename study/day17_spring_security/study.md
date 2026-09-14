# MustRemember

- Security 配置的关键骨架是（app 模块 `com.allen.questionbank.auth.SecurityConfig`，类 :21-66）：
  ```java
  http.csrf(csrf -> csrf.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .authorizeHttpRequests(a -> a
          .requestMatchers("/api/auth/login", "/actuator/health/**", "/actuator/prometheus",
                  "/error", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
          .anyRequest().authenticated())
      .exceptionHandling(...)   // 401 AUTH_REQUIRED / 403 FORBIDDEN，:50-54
      .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class); // :55
  ```
  类级 `@EnableMethodSecurity`（:22）开启 `@PreAuthorize`；BCrypt 强度由 `app.security.bcrypt-strength` 注入，默认 10（:27-30，application.yml:40）。
- 源码索引（MustRemember）：[SecurityConfig.java](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 21-66 行（过滤链 37-57、放行清单 47-49）；[ApiTokenFilter.java](../../app/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java) 第 16-39 行（Bearer 解析 22-36、AuthPrincipal record :38）
- `SecurityFilterChain` 按顺序处理请求；`permitAll`、`authenticated`、`hasRole`/`hasAnyRole` 表达不同访问门槛；ApiTokenFilter 区分无 header、错误格式、未知/过期 token 三态，后两者保持匿名交给授权层判 401
- `BCryptPasswordEncoder` 使用随机 salt 和成本因子，摘要不可逆；登录校验在 AuthService.login（:21-30，readOnly 事务），用户不存在/禁用与密码错误统一抛 `AUTH_INVALID`“用户名或密码错误”

# MustUnderstand

- CSRF 主要防护浏览器自动携带 Cookie 的认证；Bearer API 的威胁模型不同，但 token 泄露、日志泄露和重放仍需控制
- 角色控制不能证明资源归属，Service 必须再次检查 owner/student；无状态 API 不依赖服务器 Session 保存请求认证（STATELESS）
- 放行 `/actuator/health/**` 与 `/actuator/prometheus` 是有意设计：K8s 探针与 Prometheus 抓取器不带用户态 token，生产隔离靠管理端口/网络策略而非应用层认证（SecurityConfig 注释 :40-43）
- 源码索引（MustUnderstand）：[SecurityConfig.java](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 44-56 行的 CSRF、STATELESS、permitAll/anyRequest 和 entry point/access denied；[AuthService.java](../../app/src/main/java/com/allen/questionbank/auth/AuthService.java) 第 21-30 行的登录校验；[TokenService.java](../../app/src/main/java/com/allen/questionbank/auth/TokenService.java) 第 27-34 行的 resolve 过期惰性删除
