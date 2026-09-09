package com.allen.cloud.common;

import java.time.Instant;

/**
 * 跨服务统一错误契约。
 *
 * <p>拆成微服务后最容易出现的问题是"每个服务一套错误格式"：网关 502、鉴权 401、业务 409
 * 各写各的，前端要写三套解析。这里把错误体固定在契约模块，三个业务服务 + 网关共用，
 * 网关的全局过滤器也用同一个结构返回 401/429，保证调用方拿到的是同一种形状。
 *
 * @param code      稳定错误码，用于程序判断（不要靠 message 文案做分支）
 * @param message   给人看的原因，不暴露堆栈与 SQL
 * @param requestId 全链路请求 ID，网关生成后透传，用于把网关日志和下游服务日志串起来
 * @param timestamp 发生时刻，UTC
 */
public record ErrorBody(String code, String message, String requestId, Instant timestamp) {

    public static ErrorBody of(String code, String message, String requestId) {
        return new ErrorBody(code, message, requestId, Instant.now());
    }
}
