# Day15 Spring DI & Config · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `SecurityConfig`（@Bean）、`QuestionBankApplication`、[application.yml](../../app/src/main/resources/application.yml)。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点（主代码在 `app/src/main/java/com/allen/questionbank/`）：[auth/SecurityConfig.java:27-30](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) `@Bean PasswordEncoder`（强度读 app.security.bcrypt-strength）、:37-57 `@Bean SecurityFilterChain`；[QuestionBankApplication.java:7-13](../../app/src/main/java/com/allen/questionbank/QuestionBankApplication.java) 扫描入口（@EnableAsync 在 :8）；[common/InfrastructureConfig.java:12-14](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) @Configuration+@EnableScheduling（两个线程池 @Bean 在 :22/:41）；[application.yml](../../app/src/main/resources/application.yml) 环境变量占位符（:5-7）。

---

### Q2. 改一个配置项并验证 profile。
示例：把 token TTL 从默认 8h 改为测试 1h：
- 主配置 [application.yml](../../app/src/main/resources/application.yml)：`app.token-ttl: PT8H`；
- 测试 [application-test.yml](../../app/src/test/resources/application-test.yml)：`app.token-ttl: PT1H`；
- `TokenService` 构造器 `@Value("${app.token-ttl:PT8H}") Duration ttl` 注入；
- 测试类 `@ActiveProfiles("test")` 激活后断言 token 记录的过期时间为 1 小时。
验证 profile 生效的方式：启动日志打印 `The following profiles are active: test`，或在测试中 `@Autowired Environment` 调 `getActiveProfiles()`。

---

### Q3. 用 Mockito 测构造器注入。

```java
@ExtendWith(MockitoExtension.class)
class BankServiceTest {
    @Mock QuestionBankRepository banks;
    @Mock PaperVersionRepository papers;
    @Mock QuestionVersionRepository questions;
    @Mock ObjectMapper objectMapper;
    @Mock PublishedPaperCache publishedCache;                 // 缓存抽象（local/redis 同接口）
    @Mock ObjectProvider<OutboxPublisher> outboxPublisher;    // Kafka 关闭时容器内无该 Bean，用 ObjectProvider 可选注入
    @InjectMocks BankService service;   // 把 mock 从构造器注入（真实构造器共 6 个依赖，BankService.java:29-36）

    @Test
    void blankNameRejected() {
        AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);
        assertThatThrownBy(() -> service.createBank(admin, "  ", null))
            .isInstanceOf(ApiException.class);
        verify(banks, never()).save(any());   // 非法输入不落库
    }
}
```
构造器注入让依赖成为 `final` 不变量，单测不需要 Spring 容器，直接 new/Mockito 即可装配；可选依赖（OutboxPublisher 仅在 app.kafka.enabled=true 时存在）用 `ObjectProvider` 包装，避免关闭 Kafka 时启动报缺 Bean。

---

### Q4. 写出 singleton Bean 的线程安全约束和一个 `@ConditionalOnMissingBean` 条件。
- **线程安全约束**：singleton Bean 只有一个实例、被所有请求线程共享，**不能把用户/请求级数据存到成员字段**（否则线程间串数据）；请求状态放方法局部变量、参数、`SecurityContextHolder`/`ThreadLocal`。需要可变共享状态时用并发容器或无状态设计。
- **条件示例（项目真实写法）**：同一接口 `PublishedPaperCache` 有两个实现，用 `@ConditionalOnProperty` 互斥选择，切换配置即换实现、不改业务代码：
```java
@Component
@ConditionalOnProperty(name = "app.cache.backend", havingValue = "local", matchIfMissing = true) // 缺省=本地
public class LocalPublishedPaperCache implements PublishedPaperCache { ... }

@Component
@ConditionalOnProperty(name = "app.cache.backend", havingValue = "redis")                        // 显式=Redis
public class RedisPublishedPaperCache implements PublishedPaperCache { ... }
```
自研 monitor-starter 则是标准自动配置写法：`MonitorAutoConfiguration:24-25` 同时用 `@ConditionalOnClass(StatementHandler.class)`（classpath 有 MyBatis 才装配）+ `@ConditionalOnProperty("app.mybatis.monitor.enabled")`，并对内部协作 Bean 用 `@ConditionalOnMissingBean` 让用户可替换。含义：用户自定义的同类型 Bean 优先，框架默认实现让位，实现"可替换的自动配置"。

---

### Q5. 写出 Bean 生命周期顺序和构造器注入的最小类。
**生命周期**：
`BeanDefinition 注册 → 实例化(instantiate) → 属性填充/依赖注入(DI) → Aware 回调 → BeanPostProcessor 前置 → @PostConstruct 初始化 → BeanPostProcessor 后置（AOP 代理在此生成）→ 可用 → @PreDestroy / destroy 销毁`。

**构造器注入最小类（项目通用形态）**：
```java
@Service
public class BankService {
    private final QuestionBankRepository banks;   // final：不可变依赖
    public BankService(QuestionBankRepository banks) { this.banks = banks; }
}
```
单构造器时 `@Autowired` 可省略；推荐构造器注入而非字段注入：依赖不可空、可 final、便于单测、避免循环依赖被隐藏。

---

### Q6. 写代理示例，验证外部调用进切面而 self-invocation 不进，并说明 @Transactional 为何失效。

```java
@Service
class OrderService {
    @Transactional
    public void outer() { inner(); }   // ❌ self-invocation：this 是原始对象，不经过代理
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { }
}
@Service
class Caller {
    private final OrderService order;  // 注入的是 Spring 代理对象
    void run() { order.inner(); }      // ✅ 外部调用经过代理 → 切面生效
}
```
- Spring AOP 用代理包裹目标 Bean：外部调用 → 代理前置逻辑（开事务/缓存/鉴权）→ 目标方法 → 代理后置（提交事务）；项目里真实的切面是 [ops/ServiceLatencyAspect.java:23-24](../../app/src/main/java/com/allen/questionbank/ops/ServiceLatencyAspect.java)（`@Aspect @Component`，@Around 切 service 包记录方法延迟），它能生效正是因为调用方持有的是代理；
- 同类内部 `this.inner()` 直接调用原始对象、绕过代理，所以 `@Transactional/@Cacheable/@PreAuthorize` 等注解**不生效**；
- 其他失效场景：方法非 public、类未被 Spring 管理（自己 new 的）、异常被 catch 吞掉、自调用；解决：拆到另一个 Bean，或注入自身代理（`@Lazy` 自注入 / `AopContext.currentProxy()`）。事务回滚规则本身（RuntimeException 回滚、checked 异常默认不回滚）由 TransactionFailureModeTest 5 个用例固化。

---

### Q7. 写出 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 的条件含义。
- `@ConditionalOnClass(Xxx.class)`：classpath 上存在该类才装配——项目实例：monitor-starter 的 MonitorAutoConfiguration 要求存在 MyBatis 的 `StatementHandler`，app 没引 MyBatis 时整个监控自动配置静默不生效；
- `@ConditionalOnMissingBean`：容器中不存在同类型 Bean 才装配（用户自定义优先，提供默认兜底）；
- `@ConditionalOnProperty(name="app.xxx", havingValue="...", matchIfMissing=...)`：配置项满足才启用——项目实例：`app.cache.backend=local|redis` 选缓存实现（local 带 matchIfMissing=true 兜底）、`app.lock.backend=redis` 才装配 RedisGuardedPracticeSubmitter（:26）、`app.kafka.enabled` 控制 Outbox 外发链。
三者共同实现 Spring Boot"约定优于配置、可覆盖"的自动装配机制。

---

## External

### E1. 看 debug 条件报告。
启动加 `--debug`（或 `debug: true`），输出 `CONDITIONS EVALUATION REPORT`，分 Positive/Negative matches 列出每个自动配置类“为什么生效/为什么没生效”，用于排查“某个 Bean 为什么没装配”。

### E2. 比较 field / constructor injection。
- 字段注入：写起来短，但不能 final、允许可变、容器外无法注入（单测困难）、容易掩盖循环依赖、Bean 构造完可能处于“依赖未齐”状态；
- 构造器注入：依赖 final 不可变、构造即完整、必填依赖强制声明、单测直接 new、循环依赖在启动期暴露。Spring 官方推荐构造器注入；`@Setter`/`@Autowired` 字段注入仅用于可选依赖。

### E3. JDK 动态代理与 CGLIB 类代理的适用条件，并验证 self-invocation 不经过代理。
- **JDK 动态代理**：目标必须实现接口，运行期生成实现同接口的代理；
- **CGLIB**：通过生成目标类的子类做代理，不需要接口，但不能代理 final 类/ final 方法、private 方法；
- Spring Boot 2.x 起默认 CGLIB（`proxyTargetClass=true`）。
验证：在切面/事务方法第一行打印 `this.getClass()`，外部调用看到的是 `Xxx$$SpringCGLIB$$0` 代理类；self-invocation 时内层方法打印的仍是原始类名且事务不开启，即证明没走代理。
