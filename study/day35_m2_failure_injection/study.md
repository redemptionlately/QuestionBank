# 必须会背会写

- timeout 限制一次等待，retry 重复请求，circuit breaker 在失败阈值后短路，fallback 返回有限能力，bulkhead 限制线程/连接资源范围
- 可重试通常是连接瞬断、限时不可用等瞬态错误；权限、参数、唯一键冲突和数据损坏通常不可重试
- 超时预算必须小于上游 SLA；指数重试和多层重试可能形成流量乘法，必须限制次数并加入 jitter
- Resilience4j 的 retry、timeout、circuit breaker 装饰顺序会改变异常和计时语义，不能只看注解名称
- 熔断器状态机通常为 `CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN`；滑动窗口统计失败率或慢调用率，OPEN 状态拒绝调用
- 外部源码索引（会背会写）：[Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) 的状态、failure rate 和 permitted calls 配置

# 必须理解

- 故障注入验证已定义的状态转换和恢复路径，不是制造随机事故；每个实验都需要停止条件和回滚方式
- 进程存活、健康探针、业务可用、数据一致性是不同层次；数据库恢复不自动证明业务请求成功
- 外部源码索引（必须理解）：[Resilience4j TimeLimiter](https://resilience4j.readme.io/docs/timeout) 与 [Retry](https://resilience4j.readme.io/docs/retry) 的组合顺序和异常传播
- 官方：[Resilience4j](https://resilience4j.readme.io/docs/getting-started)、[Circuit Breaker](https://spring.io/projects/spring-cloud-circuitbreaker)
