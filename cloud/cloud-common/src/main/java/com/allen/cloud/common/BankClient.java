package com.allen.cloud.common;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * bank-service 的声明式 HTTP 契约。
 *
 * <p>接口放在契约模块（而不是消费方自己的包里），是 Spring Cloud OpenFeign 的推荐做法：
 * provider 与 consumer 同时依赖 common，编译期就能发现签名漂移，
 * 改一个字段名两边一起编译失败，而不是上线后 500。
 *
 * <p>name = "bank-service" 走的是服务名而不是硬编码 IP——地址由注册中心解析，
 * 客户端负载均衡在选择实例。这也是为什么本地能起两个 bank 实例做负载均衡证据。
 */
@FeignClient(name = "bank-service", path = "/internal")
public interface BankClient {

    @GetMapping("/papers/{paperId}/snapshot")
    PaperSnapshot snapshot(@PathVariable("paperId") Long paperId);
}
