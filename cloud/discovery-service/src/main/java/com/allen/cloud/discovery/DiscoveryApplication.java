package com.allen.cloud.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka 注册中心。
 *
 * <p>选 Eureka 而不是 Nacos/Consul 的理由很实际：它是纯 Java 组件，
 * Maven 依赖即可运行，本机不需要再拉一个外部中间件，证据脚本才能做到"一键复现"。
 * 生产上是否用 Eureka 是另一回事（它已进入维护模式），但注册发现的心跳、续约、
 * 自我保护、实例剔除这些语义在 Eureka 上最容易被直接观测到。
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }
}
