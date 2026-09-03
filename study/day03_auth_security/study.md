# 必须会背会写

- 认证回答主体是谁，授权回答主体能否执行动作；当前请求的 `Authentication` 保存在 `SecurityContextHolder`
- 登录服务的核心代码是：
  ```java
  UserAccount user = users.findByUsername(username)
      .filter(UserAccount::isEnabled)
      .orElseThrow(() -> unauthorized());
  if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw unauthorized();
  }
  return tokenService.issue(user);
  ```
- BCrypt 为每个密码生成 salt 并使用成本因子计算摘要；`matches(raw, encoded)` 验证输入，不保存明文
- Bearer 过滤器读取 `Authorization: Bearer <token>`，解析成功后创建 `UsernamePasswordAuthenticationToken` 并写入 SecurityContext
- 源码索引（会背会写）：[AuthController.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/AuthController.java) 第 8-22 行的登录 Controller/record；[AuthService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/AuthService.java) 第 21-29 行的用户查询、密码匹配和 token 签发

# 必须理解

- 角色只证明粗粒度能力，不能替代 `studentId/ownerId` 的资源所有权检查；Service 必须再次校验资源归属
- 内存 token 重启失效且多实例不共享；JWT 把状态放在签名载荷，集中 Session/Redis Session 把状态和撤销放到服务端
- 源码索引（必须理解）：[ApiTokenFilter.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/ApiTokenFilter.java) 第 23-35 行的过滤器链与 SecurityContext 生命周期；[SecurityConfig.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 31-55 行的无状态策略和 JSON 401/403
