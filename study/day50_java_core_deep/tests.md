# 必须会背会写的验收

- 不看答案写出 `HashMap` 的 hash 定位、扩容迁移和 `equals/hashCode` 示例，并标注复杂度与边界。
- 写出 `? extends Number` 与 `? super Integer` 的可编译读写代码；写一个破坏 hash key 稳定性的反例。
- 写出运行时注解、反射调用方法和 DTO/Entity 分离的最小示例；说明异常和访问限制。

# 额外测试与追问

- 解释为什么 `ConcurrentHashMap.computeIfAbsent` 不能自动保证跨数据库写入的幂等。
- 比较 record、普通 JavaBean 和 JPA Entity 的可变性、代理和序列化边界。
- 设计一个不接受 Java 原生反序列化的外部 API 输入模型。
