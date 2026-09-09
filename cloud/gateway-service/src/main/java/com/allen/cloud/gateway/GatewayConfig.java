package com.allen.cloud.gateway;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    /**
     * 带客户端负载均衡的 WebClient。
     *
     * <p>{@code @LoadBalanced} 的作用不是"开启重试"，而是给 Builder 装一个
     * 交换过滤器：遇到 host 是服务名（auth-service）时，先问注册中心要实例列表，
     * 再由负载均衡器选一个实例替换掉 URL 中的 host。没有它，
     * {@code http://auth-service/...} 会直接走 DNS 解析并失败。
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    /**
     * 限流维度：按客户端 IP。
     *
     * <p>真实系统里按 IP 只是最粗的一档——同一出口 NAT 后可能是一个学校/公司，
     * 误伤面很大。更合理的是"用户 + 接口"二维限流，但那要求网关认识用户身份，
     * 只能在鉴权之后做。这里为了证据可复现先用 IP。
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                java.util.Objects.requireNonNullElse(
                        exchange.getRequest().getRemoteAddress() == null
                                ? null
                                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress(),
                        "unknown"));
    }
}
