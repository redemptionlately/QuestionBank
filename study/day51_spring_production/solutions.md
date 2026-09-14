# Day51 Spring Production · 题目与标准解答（Solutions）

> Spring Boot 生产化，结合本项目启动类/SecurityConfig/Actuator。

## Current

### Q1. 写出 @ConfigurationProperties 配置类、校验注解和测试 profile，说明配置优先级。
```java
@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(
        @NotNull Duration tokenTtl,
        @Min(1) int rateLimitCapacity,
        @NotNull Duration rateLimitWindow) {}
// 启动类加 @ConfigurationPropertiesScan / @EnableConfigurationProperties(AppProperties.class)
```
[application.yml](../../app/src/main/resources/application.yml)：
```yaml
app:
  token-ttl: PT8H
  rate-limit-capacity: 300
  rate-limit-window: PT1M
```
测试用 [application-test.yml](../../app/src/test/resources/application-test.yml) + `@ActiveProfiles("test")` 覆盖为 token-ttl=PT1H、限流容量 10000、app.import.retry.enabled=false；可写 `@SpringBootTest @EnableConfigurationProperties` 断言绑定值，绑定/校验失败应在**启动阶段**直接失败（fail-fast），而不是运行到请求才报错。

**本项目真实样例**：自研 monitor-starter 的 `MyBatisQueryMonitor.MonitorProperties`（`@ConfigurationProperties(prefix = "app.mybatis")`，唯一键 slowSqlThresholdMs 默认 1000ms），由 `QuestionBankMonitorAutoConfiguration` 的 `@EnableConfigurationProperties` 注册；app 主配置 [application.yml](../../app/src/main/resources/application.yml) 显式写 `app.mybatis.slow-sql-threshold-ms: 1000`。注意项目大量配置走 `@Value("${键:默认}")` 代码级默认（如 app.rate-limit.capacity:300），条件装配键 app.cache.backend / app.rate-limit.backend / app.lock.backend / app.kafka.enabled 均带 matchIfMissing 默认值。

**配置优先级（高→低）**：命令行参数 > 系统属性/环境变量 > application-{profile}.yml > [application.yml](../../app/src/main/resources/application.yml) 默认值 > @Value 默认。本项目 `${DB_URL:默认}`、compose/k8s 用环境变量覆盖、缺省回退就是这条顺序；deploy/k8s 没有 ConfigMap，配置全部走容器 env 内联 + Secret（mysql-secret）。凭据走环境变量/密钥管理，绝不写进源码或镜像。

---

### Q2. 写接口代理与 self-invocation 反例，说明 @Transactional/日志切面为何失效。
```java
@Service class OrderService {
    @Transactional public void a(){ b(); }     // this.b()：self-invocation，不经过代理
    @Transactional(propagation=REQUIRES_NEW) public void b(){ }
}
@Service class Caller {
    final OrderService svc;                     // 注入的是代理
    void ok(){ svc.b(); }                       // 外部调用：代理拦截，切面/事务生效
}
```
失效原因：Spring AOP 用代理包裹 Bean，`this.b()` 直接调原始对象绕过代理；同类还有：方法非 public、类未交给容器（自己 new）、异常被吞、受检异常默认不触发回滚。后果：方法照常执行但**没有事务/日志/权限增强**。修复：拆到另一个 Bean、注入自身代理、或用 TransactionTemplate 编程式控制。

**本项目证据**：`TransactionFailureModeTest`（5 个 @Test）逐条固化五路径——RuntimeException 经代理回滚、self-invocation 绕过代理致回滚失效（断言注入引用是代理而方法内 this 不是）、异常被吞仍提交、受检异常默认不回滚、`rollbackFor=Exception.class` 后受检异常也回滚；`TransactionPropagationIntegrationTest` 另断言 `AopUtils.isAopProxy(bankService)=true` 且 REQUIRES_NEW 内层事务在外层回滚后独立提交。

---

### Q3. 根据启动类和安全配置画出 Bean 创建、过滤器链、Controller 调用关系。
```
SpringApplication.run（QuestionBankApplication：@SpringBootApplication + @EnableAsync）
 → 读 Environment（yml/profile/环境变量）
 → 创建 ApplicationContext、注册 BeanDefinition（含 monitor-starter 经
   AutoConfiguration.imports 导入的 QuestionBankMonitorAutoConfiguration）
 → 实例化 Bean → 构造器依赖注入（ArchitectureTest 禁止字段注入）
 → BeanPostProcessor（此处生成 AOP 事务/切面代理）→ @PostConstruct → 可用
     ├ SecurityConfig: PasswordEncoder / SecurityFilterChain（addFilterBefore
     │   把 ApiTokenFilter 放在 UsernamePasswordAuthenticationFilter 之前）
     ├ InfrastructureConfig: @EnableScheduling + import/general 两个异步线程池
     ├ BankService/PracticeService（@Service，事务代理；被 ServiceLatencyAspect 环绕）
     └ Controller/@RestController
请求（过滤器顺序按 @Order/Security 链默认序）：
 Client → RequestIdFilter(@Order(HIGHEST_PRECEDENCE)，生成/透传 X-Request-Id、写 MDC key=requestId)
        → SecurityFilterChain(-100)：ApiTokenFilter（解析 Bearer 恢复认证）→ AuthorizationFilter（URL 授权）
        → RateLimitFilter（本地固定窗口，默认；app.rate-limit.backend=redis 时换成 RedisRateLimitFilter）
        → DispatcherServlet → HandlerMapping → 参数解析+@Valid → Controller
        → @PreAuthorize（方法级）→ Service（AOP：ServiceLatencyAspect 计时 → 事务代理开启/提交）
        → Repository/Mapper → 读写分离时经 ReplicationRoutingDataSource 按只读事务路由从库 → MySQL
        → HttpMessageConverter(Jackson) 序列化 record DTO，响应头回写 X-Request-Id → 返回
```
`@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`；就绪后发布 ApplicationReadyEvent。注意 `@EnableAsync` 在启动类、`@EnableScheduling` 在 InfrastructureConfig，二者不在同一处。读写分离是 `@Profile("read-replica")` 的 ReadReplicaDataSourceConfig：master/slave 两个 HikariDataSource → ReplicationRoutingDataSource（仅“实际事务激活且只读”才路由 read，否则 write）→ 再包一层 LazyConnectionDataSourceProxy 把取连接推迟到第一条 SQL，否则 Hibernate 在事务 begin 就拿连接、readOnly 标志尚未设置，永远路由主库。

---

## External

### E1. 删除一个条件依赖后解释自动配置为何不生效。
自动配置由条件装配控制：删掉某 starter（classpath 没有对应类）→ `@ConditionalOnClass` 不满足 → 该 AutoConfiguration 整体跳过；用户自定义了同类型 Bean → `@ConditionalOnMissingBean` 让默认实现让位；`@ConditionalOnProperty` 开关为 false 也不装配。排查用启动加 `--debug` 看 CONDITIONS EVALUATION REPORT 的 Negative matches，能直接看到“为什么没装配”。

**本项目两处真实样例**：① 自研 monitor-starter 的 `QuestionBankMonitorAutoConfiguration` 同时挂 `@ConditionalOnClass(StatementHandler.class)`（classpath 无 MyBatis 则整个沉默）与 `@ConditionalOnProperty(app.mybatis.monitor.enabled, matchIfMissing=true)`，并通过 Spring Boot 2.7+ 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册（不再用 spring.factories）；其 ApplicationContextRunner 测试固化了“默认装配/属性关闭/阈值绑定”三种结果。② app 内整套 Redis/Kafka 能力都是条件装配：app.cache.backend、app.rate-limit.backend、app.lock.backend、app.kafka.enabled，缺省走本地实现或不装配，BankService 用 ObjectProvider 注入 OutboxPublisher，Kafka 关闭时为 no-op。

### E2. 设计 liveness/readiness 对数据库故障的响应策略，避免重启风暴。
- **liveness（是否需要重启）**：只检查进程内部死锁/不可自愈状态，**不要**把数据库短暂抖动纳入 liveness，否则 DB 一抖所有 Pod 被反复杀掉形成重启风暴；
- **readiness（能否接流量）**：包含数据库/依赖可用性，DB 不可用时 readiness 失败 → 从负载均衡摘除、不接新请求，但**不重启**，DB 恢复后自动恢复流量；
- 健康检查要设超时与阈值（连续 N 次失败才判定），避免瞬时网络抖动误判；本项目 `management.endpoint.health.probes.enabled=true` 已开启 liveness/readiness 探针组，且自定义 `ops/DatabaseLatencyHealthIndicator` 执行 `SELECT 1`、超过 500ms 判 DOWN（归属 readiness 语义的依赖检查，不写进 liveness）；[deploy/k8s/app.yaml](../../deploy/k8s/app.yaml) 中 readiness 探 `/actuator/health/readiness`（initialDelay 30s、failureThreshold 24，给 MySQL 启动留窗口），liveness 探 `/actuator/health/liveness`（initialDelay 60s），探针端点经 SecurityConfig 放行免 token。

### E3. Actuator 暴露、鉴权与敏感信息边界。
- 只暴露必要端点（本项目 `management.endpoints.web.exposure.include=health,info,metrics,prometheus`），生产不暴露 env/beans/heapdump（含配置、内存敏感数据）；
- 端点鉴权：SecurityConfig 仅放行 `/actuator/health/**` 与 `/actuator/prometheus`，`/api/metrics`（业务原子计数）仍需登录；health 公开组只返回 UP/DOWN（`show-details` 收敛），prometheus 端点暴露前应置于内网或经网关鉴权；
- info 不写版本密钥、env 不显示明文密码（配合配置脱敏）；profile 只选配置不是安全边界，生产杜绝默认密码、调试端点外露、各环境 schema 漂移（统一由 Flyway V1~V4 管理）。
