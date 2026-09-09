# MustRemember

- OAuth2 负责授权委托，OIDC 在其上提供身份层；JWT 是签名声明，不是自动可撤销的 session。access token 应短时有效，refresh token 要轮换、绑定客户端并支持撤销。
- JWT 校验必须固定允许的算法、issuer、audience、过期时间和 nonce/state 语义，不能只解码 payload 就信任身份。
- CORS 控制浏览器跨源读取，CSRF 针对浏览器自动携带凭据的跨站请求；无状态 Bearer API 的风险模型不同于 cookie session，但仍要正确配置来源和凭据。
- 密码使用自适应哈希（BCrypt/Argon2）保存；登录错误响应、限流和审计必须避免泄露用户是否存在。
- API 安全至少覆盖对象级授权、功能级授权、资源归属、输入校验、文件上传、SSRF、敏感数据暴露和日志脱敏。
- 外部源码索引（MustRemember）：[OAuth 2.0 RFC 6749](https://datatracker.ietf.org/doc/html/rfc6749)、[OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html)、[OWASP API Top 10](https://owasp.org/API-Security/editions/2023/en/0x00-header/)、[Spring Security OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html)

# MustUnderstand

- JWT 的签名完整性不等于撤销能力；登出、盗 token、refresh 重放和密钥轮换需要服务端状态或短 TTL 设计。
- CORS 不是鉴权，前端能否跨域读取与请求本身是否有权限是两件事；`allowCredentials=true` 不能与任意来源混用。
- 认证通过后仍必须检查资源 owner、租户和动作权限；越权漏洞通常发生在业务查询而不是登录入口。
- 外部源码索引（MustUnderstand）：[OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)、[JWT BCP RFC 8725](https://datatracker.ietf.org/doc/html/rfc8725)
