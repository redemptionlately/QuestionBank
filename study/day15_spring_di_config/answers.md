# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 改一个配置项并验证 profile
- 用 Mockito 测构造器注入
- 写出 singleton Bean 的线程安全约束和一个 `@ConditionalOnMissingBean` 条件。
- 写出 Bean 生命周期顺序和构造器注入的最小类。
- 写一个代理示例，验证外部调用能进入切面而同类 self-invocation 不进入切面，并说明 `@Transactional` 为什么可能失效。
- 写出 `@ConditionalOnClass`、`@ConditionalOnMissingBean` 和 `@ConditionalOnProperty` 的条件含义。

# External

- 看 debug 条件报告
- 比较 field/constructor injection
- 写出 JDK 动态代理与类代理的适用条件，并验证 self-invocation 不经过代理。
