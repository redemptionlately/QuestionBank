package com.allen.cloud.gateway;

import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 请求 ID 透传：网关生成 -> 下游服务 -> 响应头带回。
 *
 * <p>没有它，一次跨 3 个服务的请求在三个日志里是三串互不相干的记录，
 * 只能靠时间戳猜。有了它，grep 一个 ID 就能拿到完整链路。
 * 这也是微服务里"可观测性"最便宜的一块投入。
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
        String finalRequestId = requestId;

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(HEADER, finalRequestId)
                .build();
        exchange.getResponse().getHeaders().set(HEADER, finalRequestId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        // 必须早于鉴权过滤器：鉴权失败返回 401 时也要带上 requestId
        return -200;
    }
}
