# 必须会背会写

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
- 源码索引（会背会写）：[SecurityConfig.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/auth/SecurityConfig.java) 第 23-45 行的 `@Bean`、构造器参数和 SecurityFilterChain 创建

# 必须理解

- 自动配置通过 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 等条件决定；用户 Bean 通常覆盖缺省 Bean
- singleton Bean 被多个请求线程共享，不能在字段中保存用户、请求或临时结果；请求状态应放在局部变量或显式上下文
- 源码索引（必须理解）：[application.yml](../../../../projects/java-question-bank-m0/src/main/resources/application.yml) 的环境变量占位符和 profile 覆盖；[QuestionBankApplication.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/QuestionBankApplication.java) 第 6-9 行的组件扫描入口
- 外部源码索引（必须理解）：[Spring AOP Proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) 的代理类型与 self-invocation；[Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html) 的代理边界
