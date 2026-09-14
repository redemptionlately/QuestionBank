# Day11 Error Contract · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `GlobalExceptionHandler`、`SecurityConfig`、`ErrorResponse`、`ApiException`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（主代码在 `app/src/main/java/com/allen/questionbank/`）：[common/GlobalExceptionHandler.java:19-63](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) 6 个 `@ExceptionHandler` 分支（ApiException :23、校验 :28、约束违反 :35、缺头/坏 JSON :40、越权 :45、兜底 :50）与统一出口 response()（:59-63）；[auth/SecurityConfig.java:37-57](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 过滤器阶段错误入口（401/403 在 :50-55，writeError :59-65）；[common/ErrorResponse.java:5](../../app/src/main/java/com/allen/questionbank/common/ErrorResponse.java) 错误体字段；[ops/DatabaseLatencyHealthIndicator.java:18-19](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java) 自定义 DB 健康指标。

---

### Q2. 为 400、401、403、404、409 各写一个请求和预期业务码。

| HTTP | 触发请求示例 | 抛出位置 | 业务 code |
|---|---|---|---|
| 400 | POST /api/admin/banks，body `{"name":""}` | `@NotBlank` → MethodArgumentNotValidException | `VALIDATION_ERROR` |
| 400 | POST /api/practices/1/submit 不带 `Idempotency-Key` | Service 显式校验 | `IDEMPOTENCY_KEY_REQUIRED` |
| 400 | 请求体是坏 JSON / 缺必填 Header | HttpMessageNotReadable / MissingRequestHeader | `REQUEST_INVALID` |
| 401 | 不带 token 访问 /api/papers/published | Security authenticationEntryPoint | `AUTH_REQUIRED` |
| 401 | 登录密码错误 | AuthService | `AUTH_INVALID` |
| 403 | student 调 /api/admin/banks | @PreAuthorize / AccessDeniedHandler | `FORBIDDEN` |
| 404 | GET /api/papers/999999（不存在） | Service `notFound(...)` | `NOT_FOUND` |
| 409 | 对已 SUBMITTED 会话再保存答案 | Service 状态校验 | `STATE_CONFLICT` |

业务异常统一构造方式：
```java
throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "题目字段不合法");
```

---

### Q3. 未知异常不泄露堆栈、SQL、密码和 token。
最宽泛的兜底处理器只返回固定文案，详细信息只进服务端日志（[GlobalExceptionHandler.java:50-57](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java)）：
```java
@ExceptionHandler(Exception.class)
ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
    // 堆栈、method、URI 只进日志（带 requestId），响应体不携带
    log.error("未处理异常 {} {} (requestId={})", request.getMethod(), request.getRequestURI(),
            request.getAttribute("requestId"), e);
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", request);
}
```
另外 [application.yml:35](../../app/src/main/resources/application.yml) 中 `server.error.include-message: never` 关闭默认错误页的 message 回显；登录失败统一“用户名或密码错误”，不区分用户是否存在；响应 DTO 不含密码哈希/token 之外的敏感字段。

---

### Q4. 每个错误响应包含 requestId 和 timestamp。
统一出口（[GlobalExceptionHandler.java:59-63](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java)）：
```java
Object id = request.getAttribute("requestId");                 // RequestIdFilter 每请求写入
String requestId = id instanceof String value ? value : UUID.randomUUID().toString();
return ResponseEntity.status(status).body(new ErrorResponse(code, message, requestId, Instant.now()));
```
四个字段：`code`（程序分支用，稳定）、`message`（人读）、`requestId`（优先复用 RequestIdFilter 生成/透传并写入 MDC 的同一个值，响应头也回写 `X-Request-Id`，排障时用它 grep 日志）、`timestamp`（发生时刻，ISO-8601）。Security 层 401/403 在过滤链阶段、请求属性尚未就绪时通过 `SecurityConfig.writeError`（:59-65）用随机 UUID 输出同一结构，保证全系统错误格式一致。

---

### Q5. `/actuator/health` 状态。
- 依赖 `spring-boot-starter-actuator`，[application.yml:28](../../app/src/main/resources/application.yml) 暴露 `health,info,metrics,prometheus`，并开启探针 `management.endpoint.health.probes.enabled=true`（:32，提供 liveness/readiness 组）；
- SecurityConfig 对 `/actuator/health/**`、`/actuator/prometheus` `permitAll`（:47-48，探针与抓取器不带 token）；
- 除 Spring 自带的 db 指标外，项目自研了 `ops/DatabaseLatencyHealthIndicator`（实现 HealthIndicator，执行 `SELECT 1`，阈值 SLOW_QUERY_THRESHOLD_MS=500，超时/异常判 DOWN，details 带 probeQuery 与 latencyMs）；进程与依赖正常返回 `{"status":"UP"}`。注意：health UP 只证明**进程存活、被探针覆盖的依赖可用**，不证明登录→发布→提交业务链路成功（业务正确性要靠集成测试）。探针免 token 与 requestId 由 M0OpsIntegrationTest.containerProbesAreAccessibleWithoutToken（:28）等用例固化。

---

### Q6. 解释 HTTP 状态码与业务码为什么要同时存在。
- **HTTP 状态码**是传输/协议层语义，客户端、网关、监控、重试组件都按它做通用处理（如 401 跳登录、5xx 触发重试、4xx 不重试）；它的粒度粗，一个 400 无法区分“缺字段”还是“key 非法”。
- **业务 code** 是应用层稳定契约，精确表达失败原因（`VALIDATION_ERROR` / `IDEMPOTENCY_KEY_REQUIRED` / `AUTH_INVALID`），前端据此做差异化提示与分支，且不受 HTTP 文案、语言影响。
- 两者正交：HTTP 表达“哪一类问题”，业务 code 表达“具体哪条规则失败”，缺一不可。

---

## External

### E1. 让数据库不可用后请求接口，记录返回状态是否符合依赖故障语义。
DB 不可用时分两条路径：① 业务查询路径抛底层 `DataAccessException`，没有专门映射，落到兜底处理器返回 500 `INTERNAL_ERROR`（不应返回 200 或把 SQL 异常透出）；② 健康探测路径上，`DatabaseLatencyHealthIndicator`（ops 包，执行 `SELECT 1`）探测异常时返回 `Health.down(failure)`，`/actuator/health` 变为 DOWN（HTTP 状态 503），details 只带 probeQuery/latencyMs，不泄露连接串。该 DOWN 分支由 OpsComponentsTest.healthIndicatorReportsDownWhenDatabaseIsUnreachable（:38）固化。依赖故障属于服务端问题，语义上是 5xx，而不是 4xx。

### E2. 检查错误日志能否用 requestId 关联而不记录敏感正文。
项目真实做法：[common/RequestIdFilter.java:22-24](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) 在每请求开始时把 requestId 写入 MDC（key 固定为 `"requestId"`，:33；优先透传入站 `X-Request-Id`，否则 UUID），finally 中 `MDC.remove` 防线程复用串号（:41）；[logback-spring.xml:6](../../app/src/main/resources/logback-spring.xml) 的 pattern 含 `[%X{requestId:-}]`，每条日志都带同一 requestId；兜底处理器记录 `log.error("未处理异常 {} {} (requestId={})", method, uri, requestId, e)`。日志禁止打印明文密码、完整 token（只打前缀）、请求体中的敏感字段；客户端凭响应头/响应体里的 requestId 报障，服务端即可串起整条调用链。

### E3. 对 JSON、纯文本和空 body 分别测试错误响应格式。
- 合法 JSON 但字段非法 → 400 `VALIDATION_ERROR`；
- body 不是合法 JSON（纯文本/乱码）→ Jackson 抛 `HttpMessageNotReadableException` → 400 `REQUEST_INVALID`；
- 空 body 且 `@RequestBody` 必填 → 同样 400 `REQUEST_INVALID`；
- 三种情况响应体都必须是统一的 `ErrorResponse` JSON（含 requestId/timestamp），而不是 Spring 默认 HTML 白页——这正是 `@RestControllerAdvice` + Security 自定义 entry point 的价值。
