# Day16 Spring MVC & Validation · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `BankController`、`PracticeController`、`GlobalExceptionHandler`、record DTO。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankController` 13-65 行参数绑定、`@PreAuthorize`、record DTO；`PracticeController` 27-35 行路径变量/请求头；`GlobalExceptionHandler` 17-54 行异常分类。

---

### Q2. 测空白/超长/错误 JSON。

| 输入 | 触发注解/机制 | 异常 | 结果 |
|---|---|---|---|
| `title:""` 或全空格 | `@NotBlank` | MethodArgumentNotValidException | 400 `VALIDATION_ERROR` |
| `name` 长度 161 | `@Size(max=160)` | 同上 | 400 `VALIDATION_ERROR` |
| `questions:[]` | `@NotEmpty` | 同上 | 400 `VALIDATION_ERROR` |
| body 是 `abc`（坏 JSON） | Jackson 反序列化失败 | HttpMessageNotReadableException | 400 `REQUEST_INVALID` |
| 缺 Idempotency-Key 头 | `@RequestHeader` 必填 | MissingRequestHeaderException | 400 `REQUEST_INVALID` |
断言统一检查 `code / requestId / timestamp` 三字段存在且 code 符合预期。

---

### Q3. 断言 code/requestId/timestamp。
```java
mvc.perform(post("/api/admin/banks").header(AUTH, adminToken)
       .contentType(APPLICATION_JSON).content("{\"name\":\"\"}"))
   .andExpect(status().isBadRequest())
   .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
   .andExpect(jsonPath("$.requestId").isNotEmpty())
   .andExpect(jsonPath("$.timestamp").isNotEmpty());
```

---

### Q4. 写出含 `@RequestBody`、`@PathVariable`、`@RequestHeader`、`@Valid` 的 Controller 方法。

真实示例（提交练习）：
```java
@PostMapping("/practices/{sessionId}/submit")
public SubmitResponse submit(@PathVariable Long sessionId,
                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
    SubmitResult result = service.submit(CurrentUser.require(), sessionId, idempotencyKey);
    return SubmitResponse.from(result);
}
```
创建版本（带 `@Valid @RequestBody`）：
```java
@PostMapping("/admin/banks/{bankId}/versions")
@PreAuthorize("hasRole('ADMIN')")
public PaperResponse createDraft(@PathVariable Long bankId,
                                 @Valid @RequestBody CreatePaperRequest request) { ... }
```
- `@RequestBody`：用 HttpMessageConverter（Jackson）把 JSON 反序列化为 DTO；
- `@PathVariable`：取 URI 模板变量；
- `@RequestHeader`：取请求头；
- `@Valid`：触发 Bean Validation 级联校验。

---

### Q5. 写出嵌套 `@Valid` DTO，并区分字段校验、Service 校验、错误处理器职责。

真实 DTO：
```java
public record CreatePaperRequest(
        @NotBlank @Size(max = 200) String title,
        @NotEmpty List<@Valid QuestionInput> questions) {}   // 递归校验列表每个元素

public record QuestionInput(
        @NotBlank String prompt,
        @NotNull QuestionType type,
        @NotEmpty List<String> options,
        @NotEmpty List<String> correctAnswers,
        @Min(1) int score,
        String explanation) {}
```
**三层职责划分**：
1. **字段校验（Bean Validation + @Valid）**：只看单字段格式——非空、长度、范围、类型，不访问数据库，失败 → `VALIDATION_ERROR`；
2. **Service 业务校验**：跨字段/跨资源/状态——“标准答案必须是选项子集”“题库属于当前用户”“版本必须是 DRAFT”，需要查库，失败 → `INVALID_INPUT/FORBIDDEN/STATE_CONFLICT`；
3. **错误处理器（@RestControllerAdvice）**：把各类异常统一翻译成 `ErrorResponse`，负责 HTTP 状态、业务 code、requestId、timestamp，不写业务规则。

---

### Q6. 请求链与两个易错边界。
请求链：`DispatcherServlet → HandlerMapping（找到 handler）→ HandlerAdapter → 参数解析器（解析 body/path/header 并做校验）→ Controller 方法 → 返回值经 HttpMessageConverter 序列化为 JSON`。
- **过滤器阶段的 401 不进 MVC Advice**：Security 在 Filter 链，ControllerAdvice 管不到，必须由 `authenticationEntryPoint/accessDeniedHandler` 自己输出统一 JSON；
- **Response DTO 隔离 Entity**：接口返回 record（PaperResponse 等），不直接序列化 JPA 实体，避免内部字段（ownerId 等）泄露，也避免事务外访问懒加载关联触发 `LazyInitializationException`。

---

## External

### E1. 测未知字段、空数组、重复 query 参数。
- **未知字段**：Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`（Spring Boot 默认忽略），多传字段不报错；若要严格拒绝，配置 `spring.jackson.deserialization.fail-on-unknown-properties=true` 或 DTO 加 `@JsonIgnoreProperties(ignoreUnknown=false)`；
- **空数组**：`@NotEmpty` 拒绝 `[]`；若是可空列表应用 `@Size(min=0)` 并在 Service 处理；
- **重复 query 参数**：`?role=ADMIN&role=STUDENT` 绑定到 `List<String> role`；绑定到单值参数时取第一个（具体行为取决于转换器），非法枚举值 → 400 `REQUEST_INVALID`。
