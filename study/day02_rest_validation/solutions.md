# Day02 REST Validation · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码 `BankController.java`、`GlobalExceptionHandler.java`、`ErrorResponse.java`。

## Current

### Q1. 按源码索引逐项定位文件、类/方法，独立写出代码并口述输入、输出与边界。
固定动作（同 Day01）：定位 → 关源码默写 → 口述“输入/输出/失败边界/副作用”。本日锚点：
- `BankController` 第 20-32 行 POST 接口、第 58-65 行 record DTO；
- `GlobalExceptionHandler` 第 24-39 行校验异常/坏 JSON 到错误响应的映射；
- Spring MVC 主链：`DispatcherServlet → HandlerMapping → HandlerAdapter → 参数解析器 → Controller → HttpMessageConverter`。

---

### Q2. 为 M0 的题库列表设计分页、稳定排序、最大 page size、状态码和版本兼容策略。

**解答（设计方案）**：

1. **分页参数**：`GET /api/admin/banks?page=0&size=20`，page 从 0 开始（对齐 Spring Data `Pageable`）。
2. **稳定排序**：必须用**唯一列兜底**，不能只按可能重复的 `created_at` 排序，否则翻页会重复/丢行。写法：`ORDER BY created_at DESC, id DESC`（id 唯一，保证顺序确定）。
3. **最大 page size**：服务端强制 `size = min(size, 100)`，超过取 100；`size <= 0` 或 `page < 0` 返回 400。禁止无界 `findAll()`。
4. **状态码**：成功 200；参数非法 400（`VALIDATION_ERROR`）；未认证 401；学生访问管理接口 403。
5. **响应结构**：返回 `{content, page, size, totalElements, totalPages}`；空结果返回 `content: []` 而不是 null。
6. **版本兼容**：新增字段属于向后兼容（老客户端忽略多余字段）；删除字段、改字段类型、改语义属于破坏性变更，需要 `/api/v2/...` 或保留迁移期；错误 `code` 一经发布只增不改语义。

```java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
```

---

### Q3. 写出带 `@Valid @RequestBody` 的 POST 接口和一个嵌套 DTO。

**解答（项目真实形态）**：
```java
@PostMapping("/admin/banks/{bankId}/versions")
@PreAuthorize("hasRole('ADMIN')")
public PaperResponse createDraft(@PathVariable Long bankId,
                                 @Valid @RequestBody CreatePaperRequest request) { ... }

// 嵌套 DTO：List 上的 @NotEmpty + 元素上的 @Valid 级联校验
public record CreatePaperRequest(
        @NotBlank @Size(max = 200) String title,
        @NotEmpty List<@Valid QuestionInput> questions) {}

public record QuestionInput(
        @NotBlank String prompt,
        @NotNull QuestionType type,
        @NotEmpty List<@NotBlank String> options,
        @NotEmpty List<@NotBlank String> correctAnswers,
        @Min(1) int score,
        String explanation) {}
```
要点：`@RequestBody` 由 Jackson 反序列化，`@Valid` 触发 Bean Validation；要让嵌套集合内的元素也被校验，必须写 `List<@Valid QuestionInput>`，且外层加 `@NotEmpty`。

---

### Q4. 写出 `@PathVariable`、`@RequestHeader`、`@NotBlank`、`@NotEmpty` 的使用。

**解答（均来自真实代码）**：
```java
// @PathVariable：取路径变量
@PostMapping("/admin/versions/{paperId}/publish")
public PaperResponse publish(@PathVariable Long paperId) { ... }

// @RequestHeader：取请求头（幂等提交必须带 Idempotency-Key）
@PostMapping("/practices/{sessionId}/submit")
public SubmitResult submit(@PathVariable Long sessionId,
                           @RequestHeader("Idempotency-Key") String key) { ... }

// @NotBlank：CharSequence 非 null 且去空白后长度>0（字符串用它）
// @NotEmpty：集合/数组/字符串非 null 且 size>0（集合用它，不校验元素空白）
public record CreateBankRequest(@NotBlank @Size(max = 160) String name,
                                @Size(max = 500) String description) {}
```
区别：`@NotNull` 只判 null；`@NotEmpty` = NotNull + 长度非 0；`@NotBlank` = NotEmpty + 至少一个非空白字符。

---

### Q5. 验证缺少题库名称、空题目列表、空题干分别返回 400 和 `VALIDATION_ERROR`。

**解答（触发链路与断言）**：

| 非法输入 | 触发的注解/位置 | 抛出异常 | 处理器 | 结果 |
|---|---|---|---|---|
| `name` 缺失/空白 | `@NotBlank` | `MethodArgumentNotValidException` | `handleValidation` | 400 + code=`VALIDATION_ERROR` |
| `questions` 为空数组/null | `@NotEmpty` | 同上 | 同上 | 400 + `VALIDATION_ERROR` |
| 元素 `prompt` 空白 | 嵌套 `@Valid` + `@NotBlank` | 同上 | 同上 | 400 + `VALIDATION_ERROR` |

真实处理器（`GlobalExceptionHandler`）：
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .findFirst().map(err -> err.getField() + ": " + err.getDefaultMessage())
        .orElse("请求参数不合法");
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
}
```
MockMvc 断言示例：
```java
mvc.perform(post("/api/admin/banks").header("Authorization", bearer(admin))
        .contentType(APPLICATION_JSON).content("{\"name\":\"\"}"))
   .andExpect(status().isBadRequest())
   .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
```
注意：注解层负责“类型/空值/长度”，跨字段规则（如标准答案必须属于选项）在 Service 层再校验，抛 `ApiException(400, INVALID_INPUT)`——这是两道独立防线。

---

### Q6. 验证未知路径返回 404，内部异常不返回 SQL 或堆栈。

**解答**：
- **未知路径**：Spring MVC 找不到 Handler 会抛 `NoHandlerFoundException`/走 `/error`，应统一返回 404 + 稳定 code（如 `NOT_FOUND`），不要返回默认白页。
- **内部异常兜底**：`GlobalExceptionHandler` 最宽泛的处理器**只返回固定文案**，绝不把异常 message、SQL、堆栈写进响应：
```java
@ExceptionHandler(Exception.class)
ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
    // 详细堆栈只进日志，响应体只给固定信息，避免泄露表名/SQL/实现细节
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
}
```
- 业务层“资源不存在”显式抛 `ApiException(404, NOT_FOUND, "题库不存在")`，与未知路由的 404 区分开。

---

### Q7. 写出统一错误响应 DTO，包含稳定 code、可读 message、requestId 和时间字段。

**解答（项目真实 record）**：
```java
public record ErrorResponse(String code, String message, String requestId, Instant timestamp) {}
```
生成方式（全局处理器统一出口）：
```java
private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(
        new ErrorResponse(code, message, UUID.randomUUID().toString(), Instant.now()));
}
```
字段职责：`code` 给客户端程序做分支（稳定枚举，不随文案改动）；`message` 给人看；`requestId` 用于和服务端日志关联排障；`timestamp` 标记发生时刻。Security 层的 401/403 也复用同一结构（见 `SecurityConfig.writeError`）。

---

## External

### E1. 发送超长标题，比较接口校验、数据库长度和响应业务码。
三道防线由前到后：
1. **接口层**：`@Size(max=200)` 先拦截，返回 400 `VALIDATION_ERROR`（正常情况下到不了数据库）；
2. **业务层**：Service 可再做 `trim` 与长度判断；
3. **数据库层**：`title VARCHAR(200) NOT NULL` 是最后防线，绕过应用时由数据库拒绝并抛约束异常 → 被兜底成 500。
三者长度必须一致（DTO 的 max 与列长度对齐），避免“接口放行但数据库报错”。

### E2. 发送未知 JSON 字段，记录 Jackson 的实际行为。
Spring Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`：**未知字段被静默忽略**，不报错（例如多传 `"score":100` 会被丢弃，也防止客户端伪造字段被当成事实）。若需要严格模式，可配置 `spring.jackson.deserialization.fail-on-unknown-properties=true`，此时未知字段抛 `HttpMessageNotReadableException` → 400 `REQUEST_INVALID`。

### E3. 发送错误 Content-Type，说明请求在哪一层失败。
`@RequestBody` 依赖 `HttpMessageConverter`，缺少 `Content-Type: application/json` 或类型不匹配时，在**参数解析/消息转换阶段**就失败，抛 `HttpMediaTypeNotSupportedException`（415）或 `HttpMessageNotReadableException`（400 `REQUEST_INVALID`），根本进不了 Controller 方法体。

### E4. 设计 PUT、PATCH、DELETE 的幂等性、状态码和兼容字段。
- **PUT**：整体替换，语义幂等——同一请求执行 N 次结果相同；资源不存在时可选择创建（201）或 404，团队内统一；
- **PATCH**：局部更新，非天然幂等（如 `{"score":+1}` 自增就不幂等），应表达“设置成某值”而非增量；
- **DELETE**：幂等——删一次和删 N 次结果一致，首次 200/204，后续返回 404 或 204 需统一约定；
- 状态码：创建成功 201（本项目统一用 200），修改成功 200，删除成功 204（无响应体）；兼容策略上只新增可空字段，不改老字段类型。

### E5. 写出游标分页响应 DTO（数据、下一页游标、是否还有更多、限制单页）。
```java
public record CursorPage<T>(List<T> content, String nextCursor, boolean hasMore) {}
// nextCursor 用最后一行的 (createdAt,id) 做不透明 Base64 游标；查询时 WHERE (created_at,id) < (:ts,:id)
// LIMIT size+1：多取 1 条判断 hasMore，服务端 clamp size 上限（如 100）
```
相比 offset 分页，游标分页在数据频繁插入时不会翻页重复，适合“上拉加载更多/无限滚动”；需要跳页/显示总页数时仍用 offset 分页。
