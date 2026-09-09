# Day03 Auth & Security · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `AuthService`、`TokenService`、`ApiTokenFilter`、`SecurityConfig`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
本日锚点：`AuthController` 登录入口；`AuthService` 21-32 行查用户/比对密码/签发 token；`ApiTokenFilter` 23-35 行过滤器链；`SecurityConfig` 31-55 行无状态策略与 JSON 401/403。

---

### Q2. 写出密码摘要校验、令牌签发和 Bearer 过滤器的核心流程。

**① 登录：查用户 → BCrypt 比对 → 签发 token（真实代码）**
```java
@Transactional(readOnly = true)
public LoginResult login(String username, String password) {
    UserAccount user = users.findByUsername(username)
        .filter(UserAccount::isEnabled)   // 禁用用户直接当作认证失败
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "用户名或密码错误"));
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "用户名或密码错误");
    }
    return new LoginResult(tokenService.issue(user), user.getId(), user.getUsername(), user.getRole());
}
```
- BCrypt：每次哈希自带随机 salt 并带成本因子，**不可逆、不存明文**；验证用 `matches(明文, 库中哈希)`，相同明文两次哈希结果也不同。

**② 令牌签发（UUID 随机不透明令牌，存内存表）**
```java
public String issue(UserAccount user) {
    String token = UUID.randomUUID().toString();
    tokens.put(token, new TokenRecord(user.getId(), user.getUsername(), user.getRole(),
                                      Instant.now().plus(ttl)));
    return token;
}
```

**③ Bearer 过滤器：解析 → 构造 Authentication → 写入 SecurityContext**
```java
String header = request.getHeader("Authorization");
if (header != null && header.startsWith("Bearer ")) {
    TokenRecord record = tokenService.resolve(header.substring(7));
    if (record != null) {
        var principal = new AuthPrincipal(record.userId(), record.username(), record.role());
        var auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + record.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
chain.doFilter(request, response);
```
过滤器继承 `OncePerRequestFilter` 保证每请求只执行一次；令牌无效/过期时**不主动报错**，不写入认证信息，由后续授权层统一返回 401。

---

### Q3. 正确管理员登录返回 200、token 和 ADMIN。

请求 `POST /api/auth/login`，body `{"username":"admin","password":"admin123"}`：
- 路径在 `SecurityConfig` 中 `permitAll()` 放行；
- 成功返回 200，响应体 `LoginResult`：`{token, userId, username, role:"ADMIN"}`；
- 之后所有管理接口带 `Authorization: Bearer <token>`，过滤器把权限装成 `ROLE_ADMIN`，`@PreAuthorize("hasRole('ADMIN')")` 放行。

---

### Q4. 错误密码、禁用用户、缺少 token 分别验证 401。

| 场景 | 判定位置 | 结果 |
|---|---|---|
| 密码错误 | `passwordEncoder.matches` 为 false | 401 `AUTH_INVALID`（文案统一为“用户名或密码错误”，不暴露用户是否存在） |
| 用户被禁用（enabled=false） | `.filter(UserAccount::isEnabled)` 被过滤掉 | 401 `AUTH_INVALID`，与用户不存在同文案，防止枚举账号 |
| 缺少 token 访问受保护接口 | 无 Authentication，`authenticationEntryPoint` 接管 | 401 `AUTH_REQUIRED`「需要登录」 |
| token 非法/过期 | `tokenService.resolve` 返回 null，等同未登录 | 401 `AUTH_REQUIRED` |

---

### Q5. 学生访问管理员接口、管理员访问学生接口分别验证 403。

- **学生 → 管理接口**：已认证但角色是 `ROLE_STUDENT`，不满足 `hasRole('ADMIN')`，抛 `AccessDeniedException` → `accessDeniedHandler` 返回 403 `FORBIDDEN`；测试中 `rolesAndValidationAreEnforced` 即断言 `status().isForbidden()`。
- **管理员 → 学生接口**：`PracticeController` 类级 `@PreAuthorize("hasRole('STUDENT')")`，ADMIN 不满足，同样 403。
- 注意区分：**401 = 你是谁没确认（未认证）**；**403 = 知道你是谁但你没权限（已认证、授权失败）**。

---

### Q6. 解释认证与授权的区别。

- **认证 Authentication（你是谁）**：登录、校验凭证、建立身份，结果存入 `SecurityContextHolder`。回答“主体是谁”。
- **授权 Authorization（你能做什么）**：在已认证基础上判断角色/资源所有权，回答“能否执行这个动作”。
- 两层都不够时还要**资源所有权校验**：角色只证明粗粒度能力，Service 必须再比 `ownerId/studentId`（例如 `if (!bank.getOwnerId().equals(user.userId())) throw forbidden();`），防止“同为 ADMIN/STUDENT 就能操作他人数据”。

---

## External

### E1. 修改 token 一个字符后请求，确认不能冒充。
本项目 token 是 `UUID.randomUUID()`，作为 `ConcurrentHashMap` 的 key 精确匹配；改动任意字符后 `tokens.get(改后串)` 返回 null → 过滤器不建立认证 → 401。无法通过“改一个字符”命中他人会话。若改用 JWT，篡改一个字符会导致签名校验失败，同样被拒。

### E2. 等待或模拟过期 token，确认过滤器清理过期记录。
`TokenService.resolve` 中：记录存在但 `expiresAt` 早于当前时间时，会 `tokens.remove(token)` 惰性清理并返回 null，请求得到 401。测试可用 `@Value("${app.token-ttl}")` 把 TTL 调短（测试配置 `app.token-ttl: PT1H`），或直接调用 resolve 验证过期分支。注意：内存 token 方案重启即失效、多实例不共享，生产需要 Redis/JWT 集中管理。

### E3. 检查响应和日志中没有密码、完整 token 或内部堆栈。
- 登录失败统一文案，不回显密码、不回显“用户不存在 vs 密码错误”的差异；
- `ErrorResponse` 只含 `code/message/requestId/timestamp`，全局兜底处理器把堆栈留在服务端日志；
- 日志中不打印明文密码；token 如必须记录只打前缀（如前 8 位）；
- SecurityConfig 的 401/403 直接用 ObjectMapper 输出固定 JSON，不经过默认错误页，避免泄露内部信息。
