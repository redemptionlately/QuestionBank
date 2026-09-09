package com.allen.cloud.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;

import com.allen.cloud.common.ErrorBody;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

/**
 * 网关统一鉴权：受保护路径必须带可用 token，网关向 auth-service 验签后再放行。
 *
 * <p>三个关键决定：
 *
 * <ol>
 *   <li><b>网关只做"验签 + 透传身份"，不做业务判断。</b>拿到 userId/role 后塞进
 *       X-User-Id / X-User-Role 头，下游服务据此授权。业务规则一旦写进网关，
 *       网关就从"基础设施"变成"谁都不敢改的核心服务"。</li>
 *   <li><b>用服务名 http://auth-service 而不是写死 IP</b>，出站请求由 builder 上已带的
 *       LB 过滤器解析实例，auth 多实例自动分摊。</li>
 *   <li><b>auth-service 不可用时返回 503 而不是放行。</b>鉴权是门禁，
 *       门禁坏了只能关门，不能默认开门。</li>
 *   <li><b>每个请求都实时回 auth 验签，不做本地缓存。</b>缓存"验签通过"的结果能省一次
 *       网络往返，但角色（TEACHER/ADMIN/STUDENT）在本系统里直接决定授权边界——缓存会把
 *       "改角色立即生效"变成"改角色等缓存过期"，是反向安全。token 本身无状态、验签
 *       无副作用（auth 只读、不写库），实时验签的成本是可接受的；真正要吊销时，
 *       短 TTL + 服务端否决权是仅有的可靠手段（无状态 token 吊销困难的代价，见
 *       TokenService 的注释）。</li>
 * </ol>
 */
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    /** 需要登录的路径前缀；登录接口本身与 /internal/** 不在此列 */
    private static final List<String> PROTECTED_PREFIXES = List.of("/api/banks", "/api/practice");

    private final WebClient authClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 这里埋着一个实测才能发现的坑：注入到手里的 {@code WebClient.Builder} 并不是
     * Boot 定制过的那个。
     *
     * <p>Spring Cloud 用同名（webClientBuilder）的 {@code @LoadBalanced} builder
     * 覆盖了 Boot 的 prototype builder（bean 定义覆盖，Boot 默认允许）。所以注入的
     * builder 有两个特性：LB 过滤器已经在（服务名能解析实例，DEBUG 日志实测）；
     * 但 {@code ObservationWebClientCustomizer} 从未作用于它——裸 build() 出来的
     * WebClient 出站既不开 CLIENT span 也不带 traceparent，auth 的验签 span 全部
     * 变成孤立根 trace（Collector 实测：89 条 auth 单服务 trace，验签上下文丢失；
     * 同一次运行里网关路由请求的传播是好的，唯独验签断）。
     *
     * <p>修法不是手动挂 LB 过滤器——builder 已带，再挂同一个过滤器 = 双重解析，
     * 第二遍把实例 IP 当服务名找 "No servers available"，503，实测踩过。正确做法
     * 是链式补上 observationRegistry：这正是 ObservationWebClientCustomizer 本来
     * 会做的事（它只调 builder.observationRegistry()，不加过滤器，javap 实锤）。
     */
    public AuthFilter(WebClient.Builder builder, ObservationRegistry observationRegistry) {
        this.authClient = builder.observationRegistry(observationRegistry).build();
    }

    /** auth-service 验签结果 */
    public record Principal(Long userId, String username, String role) {
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (PROTECTED_PREFIXES.stream().noneMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_MISSING", "缺少 Bearer token");
        }

        return authClient.get()
                .uri("http://auth-service/internal/token/verify")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .bodyToMono(Principal.class)
                .flatMap(principal -> {
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header("X-User-Id", String.valueOf(principal.userId()))
                            .header("X-User-Role", principal.role())
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                // 异常分两类：auth 明确说 401（token 无效）与 连不上 auth（网络/实例故障）。
                // 前者 401 让客户端去重新登录；后者 503——鉴权不可用不能放行，也不能装作成功。
                .onErrorResume(e -> reject(exchange,
                        isUnauthorized(e) ? HttpStatus.UNAUTHORIZED : HttpStatus.SERVICE_UNAVAILABLE,
                        isUnauthorized(e) ? "TOKEN_INVALID" : "AUTH_SERVICE_DOWN",
                        isUnauthorized(e) ? "token 无效或已过期" : "auth-service 不可用，网关拒绝放行"));
    }

    private static boolean isUnauthorized(Throwable e) {
        return e instanceof WebClientResponseException w && w.getStatusCode().value() == 401;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String requestId = exchange.getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        try {
            ErrorBody body = ErrorBody.of(code, message, requestId);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return write(exchange, bytes);
        } catch (Exception e) {
            return write(exchange, ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private Mono<Void> write(ServerWebExchange exchange, byte[] bytes) {
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 晚于 RequestIdFilter（保证 401 响应也带 requestId），早于限流过滤器
        return -100;
    }
}
