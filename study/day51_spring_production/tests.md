# 必须会背会写的验收

- 写出一个 `@ConfigurationProperties` 配置类、校验注解和测试 profile；说明每个配置来源的优先级。
- 写出一个接口代理和 self-invocation 反例，说明 `@Transactional` 或日志切面为什么可能不生效。
- 根据项目启动类和安全配置画出 Bean 创建、过滤器链、Controller 调用的关系。

# 额外测试与追问

- 删除一个条件依赖后解释自动配置为何不生效。
- 设计 liveness/readiness 与数据库故障的响应策略，避免重启风暴。
- 列出 Actuator 端点的暴露、鉴权和敏感信息边界。
