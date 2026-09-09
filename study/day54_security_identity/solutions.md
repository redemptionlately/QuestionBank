# Day54 Security & Identity · 题目与标准解答（Solutions）

> 认证授权体系，对照本项目 BCrypt + 内存 Bearer token 基线。

## Current

### Q1. 写出 access/refresh token 字段、校验顺序、轮换与撤销模型。
**字段**：
- access token：主体（sub/userId）、签发者 iss、受众 aud、过期 exp、签发时间 iat、角色/scope；短期（如 15min）；
- refresh token：专属标识 jti、绑定客户端 client、更长 TTL、可旋转、可撤销。

**校验顺序（JWT 必须逐项验证，不能只 base64 解 payload 就信任）**：
1. 签名是否有效且算法在白名单（拒绝 `alg:none`，固定 RS256/ES256，防 alg 混淆）；
2. iss 是否为本服务、aud 是否包含本应用；
3. exp/nbf 时间窗口（结合时钟偏差容限）；
4. nonce/state（OIDC 流程防 CSRF/重放）；
5. 是否已被撤销（如维护黑名单/版本号）。

**轮换与撤销**：refresh token 用一次即换发新的（rotation），旧的作废；检测到旧 refresh 被再次使用 → 判定被盗，撤销整条 token 链；登出/封禁在服务端把 refresh（必要时含 access 版本号）置失效。JWT 签名完整 ≠ 可撤销，因此 access 短 TTL + 服务端可撤销的 refresh 是常见组合。本项目当前是内存 UUID token（resolve 时查 Map，过期惰性删除），属单实例教学实现。

---

### Q2. 为题库详情、管理员发布、任务查询分别写对象级/功能级/owner 授权。
| 场景 | 授权类型 | 判断位置与规则 |
|---|---|---|
| GET 题库详情/已发布试卷 | 对象级（BOLA） | 公开已发布可读；私有题库必须 `bank.ownerId == currentUser.id`，否则 404/403 |
| POST 发布版本 | 功能级 + owner | 先 `@PreAuthorize("hasRole('ADMIN')")`（功能级），Service 再判 owner（对象级） |
| GET import-jobs/{id} | 资源归属 | `findByIdAndOwnerId(id, userId)`，查不到按 404 处理，不泄露他人资源 |
原则：**认证通过不代表有权操作具体对象**；功能级角色是粗筛，对象级 owner/租户判断必须在 Service 用资源实际归属做，不能只靠前端隐藏按钮。

---

### Q3. 写出 CORS、CSRF、Bearer、cookie session 差异表；列出日志禁区。
| 机制 | 防护/作用对象 | 关键点 |
|---|---|---|
| CORS | 浏览器**跨源读取**响应 | 由服务端用 Access-Control-Allow-Origin 决定；不是鉴权；`allowCredentials=true` 时不能配 `*`，要精确来源 |
| CSRF | 浏览器**自动携带 cookie** 的跨站伪造请求 | cookie-session 需要 CSRF token/SameSite；本项目无状态 Bearer 不依赖自动 cookie，故 csrf disable |
| Bearer token | 显式放在 Authorization 头 | 不被浏览器自动携带，天然规避典型 CSRF；但要防 token 泄露（XSS/日志） |
| cookie session | 服务端会话、cookie 持会话 id | 需 HttpOnly/Secure/SameSite；有状态 |

**日志/响应禁区**：明文密码、完整 token/Authorization、答案隐私正文、数据库凭据、会话标识；错误响应不回堆栈/SQL/内部路径；登录失败统一“用户名或密码错误”，不回显用户是否存在（防账号枚举），并对登录做限流与审计。

---

## External

### E1. refresh 重放、密钥轮换、主动退出。
- **重放检测**：服务端记录每个 refresh 的状态（已用/撤销），重复使用已轮换的旧 token → 撤销该用户全部 refresh，强制重新登录；
- **密钥轮换（JWT 签名）**：用 kid 标识当前密钥，保留旧密钥一段过渡期用于验旧签、新签发用新钥（JWKS 多密钥平滑轮换）；
- **主动退出**：删除/撤销 refresh，access 因短 TTL 自然快速失效，或维护 token 版本号（登出递增版本，旧 access 校验版本不符即拒）。

### E2. 构造 IDOR 越权并指出 Service 修复位置。
攻击：学生 A 登录后把 `GET /api/import-jobs/{B的任务id}` 或 `PUT /practices/{B的sessionId}` 中的 id 改成 B 的。若 Controller 只按 id 查询不判归属就是 IDOR（OWASP API1）。修复在 **Service**：所有按 id 加载资源的方法都附加当前用户条件（`findByIdAndOwnerId`、`requireSession` 内比对 studentId），不匹配返回 404（避免存在性泄露）或 403；并加集成测试用他人 token 断言被拒。

### E3. 文件上传 / URL 抓取 / PDF 解析的 SSRF 与资源消耗防线。
- **SSRF（URL 抓取）**：用户给 URL 时禁止访问内网/元数据地址（169.254.169.254、10/172.16/192.168、localhost），解析后的真实 IP 再校验（防 DNS rebinding），限制协议为 http/https、设连接与总超时、禁止重定向到内网；
- **文件上传**：allow-list 类型、magic bytes 校验、随机 storage key 防路径穿越、限制大小/页数/并发；
- **PDF 解析**：沙箱/受限进程、解析超时、内存与递归深度上限、及时关闭流，防解压炸弹/解析器漏洞造成 CPU、内存耗尽。
