# MustRemember

- `HashMap` 使用扰动后的 hash 定位到 `(n - 1) & hash`；默认负载因子约为 0.75，扩容通常把容量翻倍，链表过长且容量达到阈值时可能树化。`HashMap` 非线程安全，`ConcurrentHashMap` 的单次容器操作线程安全，但 `get` 后 `put` 不是业务级原子操作。
- `equals` 必须满足自反、对称、传递、一致和非空；重写 `equals` 必须同时重写 `hashCode`。作为 map key 的字段应稳定，否则对象放入后修改 key 字段会导致无法定位。
- 泛型主要在编译期检查并经类型擦除实现；`? extends T` 是生产者、只能安全读取为 `T`，`? super T` 是消费者、可以写入 `T` 但读取类型只能保证为 `Object`。
- `record` 适合不可变数据载体，`sealed` 限制继承集合；它们不能替代需要受控生命周期、懒加载或持久化代理的 JPA Entity。
- 反射通过 `Class`、`Method`、`Field` 读取类型元数据并调用成员；注解的 `Retention` 决定运行时是否可见，Spring 的组件扫描和校验都依赖注解元数据。
- 序列化边界必须显式定义字段、版本和不可信输入处理；Java 原生反序列化不应直接处理外部数据，API 优先使用受约束的 JSON DTO。
- 外部源码索引（MustRemember）：[HashMap JDK 21 源码](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/share/classes/java/util/HashMap.java) 的 `putVal/getNode/resize`；[Object.equals/hashCode](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html)；[泛型通配符](https://docs.oracle.com/javase/tutorial/java/generics/wildcards.html)

# MustUnderstand

- `HashMap` 的容量、阈值、碰撞和树化是实现细节；面试回答必须区分平均复杂度与最坏情况，并说明并发场景不能用普通 `HashMap` 共享写入。
- 类型擦除意味着运行时通常不知道 `List<String>` 的元素泛型；桥接方法、堆污染和未经检查转换是泛型边界的风险。
- 反射绕过部分编译期检查并带来访问、性能和模块边界问题；Spring 代理调用的方法必须考虑 public 方法、代理类型和 self-invocation。
- DTO、Entity、缓存值和消息事件是不同边界；直接暴露 Entity 会泄露可变字段、懒加载关系和内部 schema。
- 外部源码索引（MustUnderstand）：[Java Language Specification](https://docs.oracle.com/javase/specs/jls/se21/html/)、[Java Object Serialization 安全说明](https://docs.oracle.com/en/java/javase/21/core/serialization-filtering1.html)
