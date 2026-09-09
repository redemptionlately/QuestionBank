# Day50 Java Core Deep · 题目与标准解答（Solutions）

> Java 21 语言深度，全部给可编译最小示例与边界。

## Current

### Q1. 写出 HashMap 的 hash 定位、扩容迁移、equals/hashCode 示例，标注复杂度与边界。
```java
// 定位：tab[(n-1) & hash(key)]；要求容量为 2 的幂，等价取模但更快
static final int hash(Object key) { int h; return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16); }
```
- 初始容量 16、负载因子 0.75，size 超过阈值（容量×0.75）时**容量翻倍 resize**，旧节点按高位 bit 重新分到原桶或新桶；
- 桶链表长度 ≥8 且总容量 ≥64 时树化为红黑树，节点降到 ≤6 退化为链表；
- 平均 get/put O(1)，最坏（大量碰撞）树化后 O(log n)；非线程安全，并发写可能丢数据/结构异常，并发用 ConcurrentHashMap。

```java
record Point(int x, int y) {}   // record 自动生成满足契约的 equals/hashCode
// 手写等价：
final class Point2 {
    private final int x, y;
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point2 p)) return false;
        return x == p.x && y == p.y;
    }
    @Override public int hashCode() { return 31 * x + y; } // 参与相等的字段都要进 hashCode
}
```
equals 五契约：自反、对称、传递、一致、对 null 返回 false；**重写 equals 必须同时重写 hashCode**，相等对象 hash 必须相同，否则在 HashMap 中找不到。

---

### Q2. 写出 `? extends Number` 与 `? super Integer` 的可编译读写代码；破坏 hash key 稳定性反例。
```java
// ? extends T：上界，生产者。只能读出为 T，不能写（除 null）
List<? extends Number> nums = List.of(1, 2L, 3.0);
Number n = nums.get(0);                 // 合法：读出来至少是 Number
// nums.add(1);                         // 编译错误：不知道实际元素类型，拒绝写入

// ? super T：下界，消费者。可写入 T（及其子类），读出只能当 Object
List<? super Integer> ints = new ArrayList<Number>();
ints.add(1);                            // 合法：Integer 一定是任何 ? super Integer 的子类型
Object o = ints.get(0);                 // 只能保证是 Object
```
PECS：Producer-extends（只生产/读）、Consumer-super（只消费/写）。`copy(src extends, dst super)` 是经典用法。

**破坏 hash key 稳定性反例**：
```java
class MutableKey { int v; MutableKey(int v){this.v=v;}
    public boolean equals(Object o){return o instanceof MutableKey m && m.v==v;}
    public int hashCode(){return v;} }
Map<MutableKey,String> map = new HashMap<>();
MutableKey k = new MutableKey(1); map.put(k, "x");
k.v = 2;                                // ★ 放入后修改参与 hashCode 的字段
map.get(k);                             // null：按新 hash 定位到别的桶，找不到原对象
```
作为 key 的字段必须不可变/稳定。

---

### Q3. 运行时注解 + 反射调用 + DTO/Entity 分离最小示例，说明异常与访问限制。
```java
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
@interface Audit { String value(); }

class Demo {
    @Audit("title") private String title = "t";
    public static void main(String[] args) throws Exception {
        Field f = Demo.class.getDeclaredField("title");
        Audit a = f.getAnnotation(Audit.class);   // RUNTIME 保留策略才能反射读到
        f.setAccessible(true);                    // 访问 private 需要打开（模块系统下还可能受模块边界限制）
        System.out.println(a.value() + "=" + f.get(new Demo()));
    }
}
```
- 反射异常：`NoSuchFieldException/IllegalAccessException/InvocationTargetException`（目标方法抛的异常包在其中，需 getCause 取真实异常）；
- 反射绕过编译期检查，有性能开销、破坏封装，Java 模块系统（JPMS）下还受 `opens` 限制；Spring 的组件扫描、`@Valid`、`@Entity` 都依赖运行时注解元数据。
- **DTO/Entity 分离**：Entity 含持久化状态/懒加载/代理，不能直接当响应；对外用不可变 record DTO（本项目 CreatePaperRequest/SubmitResult 都是 record），在 Service 层转换，避免泄露内部 schema 与事务外懒加载异常。

---

## External

### E1. 为什么 ConcurrentHashMap.computeIfAbsent 不能保证跨数据库写入幂等。
`computeIfAbsent` 只保证**这一个 map 操作**在 JVM 内的原子性（key 不存在时计算一次放入）。它管不到：① 跨多个实例（每个实例各自的 map）；② map 与数据库之间的两步一致性（put 成功但 DB 回滚，或 DB 提交但进程崩溃丢 map）；③ 重启丢失。跨库/跨实例幂等必须靠数据库唯一键或通用幂等表（见 Day25/30）。

### E2. 比较 record / JavaBean / JPA Entity。
| 维度 | record | JavaBean | JPA Entity |
|---|---|---|---|
| 可变性 | 不可变（字段 final） | 可变（getter/setter） | 可变（脏检查需要） |
| equals/hashCode | 按全部字段自动生成 | 默认身份相等，可手改 | 建议按主键，避免懒加载字段 |
| 代理 | 不能被 CGLIB 继承（final） | 可以 | Hibernate 需要非 final、无参构造 |
| 适用 | DTO、值对象、事件 | 一般传输/视图 | 持久化、有生命周期 |
record/sealed 不能替代 Entity：Entity 需要受管生命周期、懒加载、持久化代理，必须有无参（可 protected）构造与可变字段。

### E3. 设计拒绝原生反序列化的外部输入模型。
外部 API 只接收 JSON：用 Jackson 反序列化到**显式、受限的 record DTO**；关闭危险多态（不用 enableDefaultTyping），字段加 Bean Validation 校验；类不实现 Serializable；如确需 Java 序列化，配置 JEP 290 `ObjectInputFilter` 白名单并拒绝外部来源的 ObjectInputStream。这样从入口杜绝反序列化 gadget 链 RCE。
