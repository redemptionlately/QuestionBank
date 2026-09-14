# MustRemember

- HTTP 错误语义是：400 请求结构/参数错误，401 没有有效认证，403 已认证但无权，404 资源不存在，409 状态或幂等冲突，500 未分类服务错误
- 错误响应模型是 `ErrorResponse(code, message, requestId, timestamp)`；`code` 是稳定机器标识，`message` 是可读描述，`requestId` 关联日志
- 过滤器阶段的 401/403 由 Security `authenticationEntryPoint/accessDeniedHandler` 写出；Controller/Service 抛出的 `ApiException` 由 `@RestControllerAdvice` 转换
- 异常转换的代码形态是：
  ```java
  @RestControllerAdvice
  class GlobalExceptionHandler {
      @ExceptionHandler(ApiException.class)
      ResponseEntity<ErrorResponse> handle(ApiException e, HttpServletRequest req) {
          return ResponseEntity.status(e.status())
              .body(new ErrorResponse(e.code(), e.getMessage(), requestId(req), Instant.now()));
      }
  }
  ```
- 源码索引（MustRemember）：[GlobalExceptionHandler.java](../../app/src/main/java/com/allen/questionbank/common/GlobalExceptionHandler.java) 第 19-63 行的 6 个 `@ExceptionHandler` 分支和统一 `response` 方法（:59-63，requestId 优先取请求属性）；[RequestIdFilter.java](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) 第 22-41 行的 requestId 生成/透传与 MDC 写入

# MustUnderstand

- 错误处理器必须避免返回密码、token、SQL、堆栈和内部路径；健康探针只能证明进程/依赖探针状态，不能证明登录、发布和提交链路成功
- 源码索引（MustUnderstand）：[SecurityConfig.java](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 37-65 行的过滤器错误入口与 writeError；[ErrorResponse.java](../../app/src/main/java/com/allen/questionbank/common/ErrorResponse.java) 第 5 行的响应字段；[DatabaseLatencyHealthIndicator.java](../../app/src/main/java/com/allen/questionbank/ops/DatabaseLatencyHealthIndicator.java) 的 SELECT 1 健康探测（500ms 阈值，异常判 DOWN）
