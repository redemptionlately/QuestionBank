# MustRemember

- `DispatcherServlet -> HandlerMapping -> HandlerAdapter -> 参数解析器 -> Controller -> HttpMessageConverter` 是 Spring MVC 的主调用链
- Controller 的最小请求绑定骨架是：
  ```java
  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(
          @Valid @RequestBody LoginRequest request) {
      AuthService.LoginResult result = authService.login(request.username(), request.password());
      return ResponseEntity.ok(new LoginResponse(
          result.token(), result.userId(), result.username(), result.role()));
  }
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
  ```
- `@PathVariable` 读取路径变量，`@RequestBody` 由 Jackson 反序列化，`@RequestHeader` 读取请求头；`@Valid` 触发 Bean Validation
- HTTP 方法语义必须区分：GET/HEAD 读取，POST 通常创建或触发命令，PUT 替换且可设计为幂等，PATCH 局部更新，DELETE 删除；状态码必须表达成功、客户端错误和服务端错误
- 列表接口至少定义 `page/size` 或 cursor、稳定排序、最大 page size、总数是否精确和空结果格式；不能把无界查询直接交给数据库
- API 版本、字段兼容和错误 code 属于协议；新增字段通常向后兼容，删除/改类型需要版本或迁移期
- 源码索引（MustRemember）：[BankController.java](../../src/main/java/com/allen/questionbank/bank/BankController.java) 第 20-32 行的管理员接口、第 58-65 行的 DTO/record；重点是注解、参数绑定和 DTO 构造

# MustUnderstand

- 输入校验负责类型、空值和长度，Service 校验跨字段/资源/状态，数据库约束防止并发和绕过应用后的非法事实
- Response DTO 隔离 Entity 的内部字段和懒加载关系；错误 `code` 面向客户端分支，`requestId` 用于日志关联
- 源码索引（MustUnderstand）：[GlobalExceptionHandler.java](../../src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) 第 24-39 行的校验、缺 Header 和坏 JSON 异常映射
