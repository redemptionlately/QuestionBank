package com.allen.cloud.practice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.allen.cloud.common.ErrorBody;

/** 与 bank-service、网关共用同一错误形状（ErrorBody），调用方只需一套解析逻辑。 */
@RestControllerAdvice
public class ErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandling.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorBody> handle(ResponseStatusException e, jakarta.servlet.http.HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(ErrorBody.of(status.name(), e.getReason() == null ? status.getReasonPhrase() : e.getReason(),
                        request.getHeader("X-Request-Id")));
    }

    /** 缺少网关透传头（X-User-Id 等）是客户端问题，必须 400 而不是 500 */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorBody.of("MISSING_HEADER", "缺少请求头 " + e.getHeaderName(),
                        request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        // 响应只回 requestId，堆栈必须留在服务端日志里，否则 500 无处可查（与 bank-service 同款）
        log.error("未处理异常 {} {} (requestId={})", request.getMethod(), request.getRequestURI(),
                request.getHeader("X-Request-Id"), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorBody.of("INTERNAL_ERROR", "服务内部错误", request.getHeader("X-Request-Id")));
    }
}
