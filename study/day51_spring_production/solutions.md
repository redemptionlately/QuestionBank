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
`application.yml`：
```yaml
app:
  token-ttl: PT8H
  rate-limit-capacity: 300
  rate-limit-window: PT1M
```
测试用 `application-test.yml` + `@ActiveProfiles("test")` 覆盖为 token-ttl=PT1H、限流容量 10000；可写 `@SpringBootTest @EnableConfigurationProperties` 断言绑定值，绑定/校验失败应在**启动阶段**直接失败（fail-fast），而不是运行到请求才报错。

**配置优先级（高→低）**：命令行参数 > 系统属性/环境变量 > application-{profile}.yml > application.yml 默认值 > @Value 默认。本项目 `${DB_URL:默认}` 就是环境变量覆盖、缺省回退。凭据走环境变量/密钥管理，绝不写进源码或镜像。

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
失效原因：Spring AOP 用代理包裹 Bean，`this.b()` 直接调原始对象绕过代理；同类还有：方法非 public、类未交给容器（自己 new）、异常被吞。后果：方法照常执行但**没有事务/日志/权限增强**。修复：拆到另一个 Bean、注入自身代理、或用 TransactionTemplate 编程式控制。

---

### Q3. 根据启动类和安全配置画出 Bean 创建、过滤器链、Controller 调用关系。
```
SpringApplication.run
 → 读 Environment（yml/profile/环境变量）
 → 创建 ApplicationContext、注册 BeanDefinition
 → 实例化 Bean → 依赖注入（构造器）→ BeanPostProcessor（此处生成 AOP 代理）→ @PostConstruct → 可用
     ├ SecurityConfig: PasswordEncoder / SecurityFilterChain（装配 ApiTokenFilter）
     ├ BankService/PracticeService（@Service，事务代理）
     └ Controller/@RestController
请求：
 Client → RateLimitFilter → ApiTokenFilter（恢复认证）→ AuthorizationFilter（URL 授权）
        → DispatcherServlet → HandlerMapping → 参数解析+@Valid → Controller
        → @PreAuthorize（方法级）→ Service（事务代理开启/提交事务）→ Repository → MySQL
        → HttpMessageConverter(Jackson) 序列化 DTO → 返回
```
`@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`；就绪后发布 ApplicationReadyEvent。

---

## External

### E1. 删除一个条件依赖后解释自动配置为何不生效。
自动配置由条件装配控制：删掉某 starter（classpath 没有对应类）→ `@ConditionalOnClass` 不满足 → 该 AutoConfiguration 整体跳过；用户自定义了同类型 Bean → `@ConditionalOnMissingBean` 让默认实现让位；`@ConditionalOnProperty` 开关为 false 也不装配。排查用启动加 `--debug` 看 CONDITIONS EVALUATION REPORT 的 Negative matches，能直接看到“为什么没装配”。

### E2. 设计 liveness/readiness 对数据库故障的响应策略，避免重启风暴。
- **liveness（是否需要重启）**：只检查进程内部死锁/不可自愈状态，**不要**把数据库短暂抖动纳入 liveness，否则 DB 一抖所有 Pod 被反复杀掉形成重启风暴；
- **readiness（能否接流量）**：包含数据库/依赖可用性，DB 不可用时 readiness 失败 → 从负载均衡摘除、不接新请求，但**不重启**，DB 恢复后自动恢复流量；
- 健康检查要设超时与阈值（连续 N 次失败才判定），避免瞬时网络抖动误判；本项目 `/actuator/health` 已暴露，生产应区分 liveness/readiness 探针组（`management.endpoint.health.probes.enabled`）并对端点鉴权。

### E3. Actuator 暴露、鉴权与敏感信息边界。
- 只暴露必要端点（本项目 health,info,metrics），生产不暴露 env/beans/heapdump（含配置、内存敏感数据）；
- 端点放到独立管理端口或经网关鉴权，health 可公开但只返回 UP/DOWN（`show-details=when-authorized`）；
- info 不写版本密钥、env 不显示明文密码（配合配置脱敏）；profile 只选配置不是安全边界，生产杜绝默认密码、调试端点外露、各环境 schema 漂移（统一由 Flyway 管理）。
