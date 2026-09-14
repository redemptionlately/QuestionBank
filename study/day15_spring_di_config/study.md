# MustRemember

- IoC 生命周期是 `BeanDefinition -> instantiate -> dependency injection -> post-process -> @PostConstruct -> usable -> destroy`
- `@Service`、`@Repository`、`@Component` 是扫描候选；构造器注入表达必需依赖，`@Bean` 方法适合第三方对象；默认 scope 是 singleton
- 配置由 yml、profile、环境变量和命令行参数组成 PropertySource；同一属性存在多个来源时按 Spring 配置优先级合并
- Spring AOP 通过代理包裹目标对象；外部调用可进入切面，同类 `self-invocation` 直接调用目标方法不会经过代理；`@Transactional`、缓存和权限注解都必须先判断代理是否生效
- JDK 动态代理要求接口，CGLIB/字节码代理可代理类；代理对象与目标对象不是同一实例，最终调用链应区分代理前置逻辑、目标方法和后置逻辑
- 构造器注入源码形态是：
  ```java
  @Service
  class BankService {
      private final PaperVersionRepository papers;
      BankService(PaperVersionRepository papers) { this.papers = papers; }
  }
  ```
- 源码索引（MustRemember）：[SecurityConfig.java](../../app/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 27-30 行的 PasswordEncoder `@Bean`、第 37-57 行的 SecurityFilterChain；条件装配真实样例：[LocalPublishedPaperCache.java](../../app/src/main/java/com/allen/questionbank/cache/LocalPublishedPaperCache.java) 第 14-16 行与 [RedisPublishedPaperCache.java](../../app/src/main/java/com/allen/questionbank/cache/RedisPublishedPaperCache.java) 第 29-31 行的 @ConditionalOnProperty 互斥选择

# MustUnderstand

- 自动配置通过 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 等条件决定；用户 Bean 通常覆盖缺省 Bean
- singleton Bean 被多个请求线程共享，不能在字段中保存用户、请求或临时结果；请求状态应放在局部变量或显式上下文
- 源码索引（MustUnderstand）：[application.yml](../../app/src/main/resources/application.yml) 的环境变量占位符和 profile 覆盖；[QuestionBankApplication.java](../../app/src/main/java/com/allen/questionbank/QuestionBankApplication.java) 第 7-13 行的组件扫描入口；[ServiceLatencyAspect.java](../../app/src/main/java/com/allen/questionbank/ops/ServiceLatencyAspect.java) 的 @Around 切面是代理生效的真实样例；[InfrastructureConfig.java](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) 第 12-14 行 @Configuration/@EnableScheduling 与线程池 @Bean
- 外部源码索引（MustUnderstand）：[Spring AOP Proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) 的代理类型与 self-invocation；[Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html) 的代理边界
