# 必须会背会写

- Spring Boot 启动过程可概括为读取 Environment、创建 ApplicationContext、注册 BeanDefinition、实例化并后处理 Bean，最后发布应用就绪事件；`@SpringBootApplication` 组合了配置、自动配置和组件扫描。
- 自动配置由条件注解决定：`@ConditionalOnClass`、`@ConditionalOnMissingBean` 和属性条件共同判断是否创建默认 Bean；用户自定义 Bean 通常覆盖缺省实现。
- 配置优先级必须区分配置文件、profile、环境变量、系统属性和命令行参数；凭据使用环境注入或密钥管理，不写入源码和镜像。
- AOP 代理在目标方法外织入事务、日志和权限；JDK 动态代理面向接口，CGLIB/Byte Buddy 可代理类，self-invocation 不经过外层代理。
- `@ConfigurationProperties` 将分组配置绑定为类型安全对象；配置校验失败应在启动阶段暴露，而不是请求运行时才失败。
- Actuator 的 health、info、metrics 暴露面必须鉴权和限制；readiness 表示能否接流量，liveness 表示进程是否需要重启，二者不是同一个探针。
- 外部源码索引（会背会写）：[Spring Boot auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html)、[@ConfigurationProperties](https://docs.spring.io/spring-boot/reference/features/external-config.html)、[Spring AOP Proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)

# 必须理解

- Bean 循环依赖、条件装配顺序、代理类型和初始化回调会影响启动结果；看到一个注解不能直接推断其生效，必须追踪 Bean 是否被容器创建和调用是否经过代理。
- profile 只选择配置，不是安全边界；生产配置必须避免默认密码、调试端点外露和不同环境 schema 漂移。
- AOP 只适合横切逻辑；把核心业务状态藏在切面会降低可读性和测试性。事务代理失效时，方法仍会执行但不会获得预期事务。
- 健康检查应检查真正的依赖可用性并设置超时，不能让 liveness 因数据库短暂故障导致重启风暴。
- 外部源码索引（必须理解）：[Spring ApplicationContext](https://docs.spring.io/spring-framework/reference/core/beans.html)、[Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator.html)
