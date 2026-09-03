# 必须会背会写

- Security 配置的关键骨架是：
  ```java
  http.csrf(csrf -> csrf.disable())
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .authorizeHttpRequests(a -> a
          .requestMatchers("/api/auth/login", "/actuator/health").permitAll()
          .anyRequest().authenticated())
      .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
  ```
- 源码索引（会背会写）：[SecurityConfig.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 23-45 行的安全链；[ApiTokenFilter.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java) 第 23-35 行的 Bearer 解析
- `SecurityFilterChain` 按顺序处理请求；`permitAll`、`authenticated`、`hasRole`/`hasAnyRole` 表达不同访问门槛
- `BCryptPasswordEncoder` 使用随机 salt 和成本因子，摘要不可逆；Bearer 过滤器区分无 header、错误格式、未知 token

# 必须理解

- CSRF 主要防护浏览器自动携带 Cookie 的认证；Bearer API 的威胁模型不同，但 token 泄露、日志泄露和重放仍需控制
- 角色控制不能证明资源归属，Service 必须再次检查 owner/student；无状态 API 不依赖服务器 Session 保存请求认证
- 源码索引（必须理解）：[SecurityConfig.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 34-44 行的 CSRF、STATELESS、permitAll/anyRequest 和 entry point/access denied
