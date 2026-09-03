# 必须会背会写

- `DispatcherServlet -> HandlerMapping -> HandlerAdapter -> 参数解析器 -> Controller -> HttpMessageConverter` 是 Spring MVC 请求链
- Controller 方法骨架是 `public ResponseEntity<?> create(@Valid @RequestBody CreateRequest body, @PathVariable Long id)`；`@Valid` 递归校验嵌套对象
- Bean Validation 处理字段格式，Service 处理跨字段、资源归属和状态；缺 Header、坏 JSON、字段错误对应不同异常类型
- M0 请求 DTO 的源码形态是：
  ```java
  public record CreatePaperRequest(
      @NotBlank @Size(max = 200) String title,
      @NotEmpty List<@Valid QuestionInput> questions) {}
  ```
- 源码索引（会背会写）：[BankController.java](../../src/main/java/com/allen/questionbank/bank/BankController.java) 第 20-65 行的参数绑定、`@PreAuthorize` 和 record DTO；[PracticeController.java](../../src/main/java/com/allen/questionbank/practice/PracticeController.java) 第 27-35 行的路径变量/请求头

# 必须理解

- 过滤器阶段的 401 不会自然进入 MVC Advice；Security 必须提供 JSON entry point，MVC/Service 异常由 ControllerAdvice 处理
- Response DTO 防止 Entity 内部字段泄露，并切断懒加载关系在事务外序列化的边界
- 源码索引（必须理解）：[GlobalExceptionHandler.java](../../src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) 第 19-44 行的异常分类与 HTTP 状态映射
