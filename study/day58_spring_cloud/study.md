# MustRemember

- 微服务拆分应按业务边界和数据所有权拆分；服务之间通过稳定 API/事件通信，不能因为类数量增加就机械拆服务。
- 服务发现维护实例列表与健康状态；客户端负载均衡选择实例，网关负责路由、认证、限流和统一入口，但不应承载核心领域规则。
- OpenFeign 将 HTTP 调用声明为接口；调用必须设置连接/读取超时、重试上限、幂等约束和错误映射，不能把网络调用当成本地方法。
- 配置中心的配置版本、刷新范围和敏感字段边界必须明确；配置变更需要审计、回滚和灰度。
- 熔断器状态通常是 `CLOSED -> OPEN -> HALF_OPEN`；bulkhead 限制线程/连接隔离，timeout 限制等待，retry 只适用于明确的瞬态故障。
- 外部源码索引（MustRemember）：[Spring Cloud Gateway](https://docs.spring.io/spring-cloud-gateway/reference/)、[Spring Cloud OpenFeign](https://docs.spring.io/spring-cloud-openfeign/reference/)、[Spring Cloud CircuitBreaker](https://docs.spring.io/spring-cloud-circuitbreaker/reference/)

# MustUnderstand

- 服务发现、网关、负载均衡、熔断和重试共同改变请求路径；多层 retry 可能造成流量乘法，必须设总预算。
- 微服务不能自动获得高可用；跨服务事务、数据复制、版本兼容、链路追踪和部署顺序反而更复杂。
- 单体模块化和微服务是取舍，不是高低等级；M0 当前选择模块化单体以保持事务和数据事实清晰。
- 外部源码索引（MustUnderstand）：[Spring Cloud architecture](https://spring.io/projects/spring-cloud)、[Resilience4j retry](https://resilience4j.readme.io/docs/retry)
