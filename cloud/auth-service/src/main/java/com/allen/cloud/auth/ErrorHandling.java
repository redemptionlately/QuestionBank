package com.allen.cloud.auth;

import com.allen.cloud.common.ErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一错误契约：与 bank/practice/网关同一结构（cloud-common ErrorBody），
 * 调用方只需解析一种形状。此前 auth 是 cloud 唯一没有全局异常处理的业务服务。
 */
@RestControllerAdvice
public class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    /** 参数校验失败（@Valid）：400 + 字段级原因，错误码与 app 单体 GlobalExceptionHandler 对齐 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of("VALIDATION_ERROR", message, request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorBody> handle(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(ErrorBody.of(status.name(), e.getReason() == null ? status.getReasonPhrase() : e.getReason(),
                        request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e, HttpServletRequest request) {
        // 响应只回 requestId，不暴露堆栈与 SQL；服务端必须把异常记下来，否则 500 成了黑洞
        log.error("未处理异常 {} {} (requestId={})", request.getMethod(), request.getRequestURI(),
                request.getHeader("X-Request-Id"), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorBody.of("INTERNAL_ERROR", "服务内部错误", request.getHeader("X-Request-Id")));
    }
}
