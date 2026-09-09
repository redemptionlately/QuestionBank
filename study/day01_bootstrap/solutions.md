# Day01 Bootstrap · 题目与标准解答（Solutions）

> 题目来源：`tests.md`；解答依据：当天 `study.md` + 项目真实源码（`src/main/java`、`pom.xml`）。

---

## Current

### Q1. 按 study.md 的“源码索引（MustRemember/ MustUnderstand）”逐项定位文件、类/方法、SQL 对象和行号，独立写出代码并口述输入、输出与边界。

**解答（执行方法）**：这是每天的固定动作，按“定位 → 默写 → 口述 I/O 边界”三步走：

1. **定位**：根据 study.md 给出的相对路径（如 `../../src/main/java/com/allen/questionbank/bank/BankService.java`）在 IDEA 中用 `Ctrl+Shift+N` 打开文件，跳到指定行号（`Ctrl+G`）。
2. **默写**：关掉源码，新建空白文件独立写出，不能只写方法签名。
3. **口述边界**：对每个方法说清四件事——输入是什么（谁调用、参数约束）、输出是什么（返回值/DTO）、失败边界（抛什么异常、对应什么 HTTP 状态码）、副作用（是否写库、是否改缓存）。

本项目 Day01 必须能定位的锚点：
- 启动类 `QuestionBankApplication.java`（`@SpringBootApplication` + `@EnableAsync` + `SpringApplication.run`）
- 实体 `bank/QuestionBank.java`、仓储 `bank/QuestionBankRepository.java`
- 业务 `bank/BankService.java`（构造器注入、校验、`save`）
- 接口 `bank/BankController.java`（路由、`@PreAuthorize`、DTO record）
- `pom.xml` 的 parent 与 dependencies

---

### Q2. 写出一套完整的 QuestionBank 三层代码（Entity / Repository / Service / Controller），不能只写方法签名。

**解答**：以下是**项目真实代码**（省略 package，import 由 IDE 自动补全）。

**Entity —— `bank/QuestionBank.java`**
```java
@Entity
@Table(name = "question_bank")
public class QuestionBank {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuestionBank() {}                       // JPA 反射实例化必须有无参构造
    public QuestionBank(Long ownerId, String name, String description) {
        this.ownerId = ownerId; this.name = name;
        this.description = description; this.createdAt = Instant.now();
    }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

**Repository —— `bank/QuestionBankRepository.java`**
```java
public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {}
```
继承后自动拥有 `save / findById / findAll / count / delete` 等方法，泛型第二个参数是主键类型 `Long`。

**Service —— `bank/BankService.java`（创建片段）**
```java
@Service
public class BankService {
    private final QuestionBankRepository banks;
    // 构造器注入：依赖成为对象不变量，final 保证不再被替换
    public BankService(QuestionBankRepository banks, /* 其余依赖略 */) {
        this.banks = banks;
    }

    @Transactional   // 写方法开启事务，未捕获的 RuntimeException 会回滚
    public QuestionBank createBank(ApiTokenFilter.AuthPrincipal user, String name, String description) {
        if (user == null || name == null || name.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "题库名称不能为空");
        return banks.save(new QuestionBank(user.userId(), name.trim(), description));
    }
}
```

**Controller —— `bank/BankController.java`（创建片段）**
```java
@RestController
@RequestMapping("/api")
public class BankController {
    private final BankService service;
    public BankController(BankService service) { this.service = service; }

    @PostMapping("/admin/banks")
    @PreAuthorize("hasRole('ADMIN')")                       // 方法级授权
    public BankResponse createBank(@Valid @RequestBody CreateBankRequest request) {
        QuestionBank bank = service.createBank(CurrentUser.require(), request.name(), request.description());
        return new BankResponse(bank.getId(), bank.getName(), bank.getDescription()); // Entity→DTO 投影
    }

    // 请求 DTO：@Valid 级联校验；响应 DTO：不暴露 ownerId/status 等内部字段
    public record CreateBankRequest(@NotBlank @Size(max = 160) String name,
                                    @Size(max = 500) String description) {}
    public record BankResponse(Long id, String name, String description) {}
}
```

**完整数据流**：JSON → Controller 参数绑定/`@Valid` 校验 → Service 取当前用户 + 业务规则 → Repository `save` → 数据库 → Service 返回实体 → Controller 转 DTO → JSON。找不到、越权、非法状态都必须抛异常映射成错误响应，不能伪装成功。

---

### Q3. 说明 `mvn test`、`mvn package`、`mvn spring-boot:run` 的输入、输出和副作用。

| 命令 | 输入 | 输出 | 副作用 |
|---|---|---|---|
| `mvn test` | `pom.xml`、`src/main`、`src/test` | 控制台结果 `Tests run: X...`；`target/surefire-reports/` 报告；编译 class 到 `target/classes`、`target/test-classes` | 执行全部单元/集成测试；测试失败立即中断，退出码非 0；**不打 JAR** |
| `mvn package` | 同上 | `target/question-bank-m0-0.1.0-SNAPSHOT.jar`（spring-boot 插件打成可执行 fat JAR） | 先跑完整 test 生命周期（失败则不打包），再编译+打包；写满 `target/` |
| `mvn spring-boot:run` | `pom.xml` + 主源码 | 启动内嵌 Tomcat，控制台打印 Spring Banner 与启动日志，对外提供 HTTP 服务 | 编译主代码（**不跑测试**）；启动一个前台 JVM 进程并监听端口；`Ctrl+C` 停止；不产出 JAR |

生命周期顺序记忆：`compile → test-compile → test → package → install → deploy`；`spring-boot:run` 是插件目标（goal），不属于上述生命周期链。

---

### Q4. 指出 pom.xml 中运行时依赖和测试依赖，解释 runtime 与 test scope 的区别。

**本项目 `pom.xml` 真实依赖分类**：

- **runtime（运行时依赖，1 个）**：`com.mysql:mysql-connector-j`（`<scope>runtime</scope>`）。代码编译期不直接 import 驱动类，只在运行时由 JDBC 自动装配加载。
- **test（测试依赖，3 个）**：
  - `com.h2database:h2`（测试用内存数据库）
  - `org.springframework.boot:spring-boot-starter-test`（JUnit5、MockMvc、AssertJ 等）
  - `org.springframework.security:spring-security-test`
- **无 scope 标注（默认 compile）**：`starter-web`、`starter-validation`、`starter-data-jpa`、`starter-security`、`starter-actuator`、`flyway-core`、`flyway-mysql`——编译/测试/运行都需要，并打进最终 JAR。

**scope 区别**：

| scope | 编译主代码 | 测试编译/运行 | 打进 JAR / 运行时 |
|---|---|---|---|
| compile（默认） | ✅ | ✅ | ✅ |
| runtime | ❌ | ✅ | ✅（运行时需要） |
| test | ❌ | ❌（仅测试侧） | ❌ |

一句话：`runtime` 是“编译不用、运行要用、会打包”；`test` 是“只在 src/test 可见、主程序完全不需要、不打包”。

---

### Q5. 运行 `mvn test`，解释退出码、测试数量和首个失败原因。

**运行方式**（项目根目录）：
```bash
./mvnw -B test          # macOS/Linux
mvnw.cmd -B test        # Windows；或本机装了 Maven 用 mvn -B test
```

**退出码**：
- `0`：BUILD SUCCESS，全部测试通过；
- `1`：存在编译错误、断言失败（Failure）或异常错误（Error），BUILD FAILURE。

**本项目测试数量（来自 `src/test/java/com/allen/questionbank/`，事实统计）**：
- `InfrastructureUnitTest`：1 个方法 `cacheLoadsOnceUntilEvicted`
- `RateLimitFilterTest`：1 个方法 `rejectsRequestsBeyondFixedWindowCapacity`（固定窗口限流 429 + Retry-After + RATE_LIMITED）
- `M0OpsIntegrationTest`：1 个方法 `metricsEndpointExposesAtomicCountersForAuthenticatedUser`（`/api/metrics` 计数指标 + 未登录 401）
- `M0FlowIntegrationTest`：8 个方法（幂等提交、角色与校验、三种题型判分、坏请求 400、草稿事务回滚、同 key 并发、查询索引、异步导入任务）
- **合计 11 个测试方法**；成功时末尾应出现 `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`。

**首个失败原因定位顺序**：
1. 控制台第一个 `[ERROR]` 块，记录失败的“类名#方法名”；
2. 打开 `target/surefire-reports/<全类名>.txt`，看 `FAILURE!` 后的断言差异或堆栈第一行业务代码；
3. 本项目集成测试用 `@ActiveProfiles("test")` + H2 内存库 + Flyway，常见首因：
   - Flyway 建表 SQL 与 H2 `MODE=MySQL` 不兼容导致上下文启动失败（属 Error，不是 Failure）；
   - `spring.jpa.hibernate.ddl-auto=validate` 下实体字段与表列对不上；
   - 断言的 JSON 字段/状态码与实际不符（属 Failure）。
- 区分：**Failure = 断言不成立**；**Error = 测试代码抛了未预期异常**。

---

### Q6. 写出 Java 方法按值传递、final 引用和 equals/hashCode 契约的最小示例。

```java
public class ValueFinalEqualsDemo {
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point p)) return false;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return 31 * x + y; } // 与 equals 联动
    }

    static void mutate(Point p) { p.x = 99; }                 // 改的是同一对象的字段
    static void reassign(Point p) { p = new Point(0, 0); }    // 只改局部副本的指向

    public static void main(String[] args) {
        Point a = new Point(1, 2);
        final Point b = a;
        // b = new Point(3,4);   // 编译错误：final 变量不能重新赋值
        b.x = 5;                 // 合法：final 只锁引用，不锁对象内部状态

        mutate(a);
        System.out.println(a.x); // 99：引用副本指向同一对象，字段被改
        reassign(a);
        System.out.println(a.x); // 99：方法内重新指向不影响外部

        Point c = new Point(1, 2), d = new Point(1, 2);
        System.out.println(c.equals(d));                 // true：逻辑相等
        System.out.println(c == d);                      // false：两个堆对象
        System.out.println(c.hashCode() == d.hashCode());// true：契约要求
    }
}
```
要点：Java 只有按值传递；对象传递的是“引用值的副本”；`final` 禁止变量再绑定；**equals 相等的两个对象 hashCode 必须相等**（否则放进 HashMap 会找不到）。

---

### Q7. 写出泛型 `? extends` 与 `? super` 的读写示例，并说明 ArrayList、HashMap、ConcurrentHashMap 适用场景。

```java
List<? extends Number> ext = new ArrayList<Integer>(List.of(1, 2, 3));
Number n = ext.get(0);     // ✅ 读：元素至少是 Number
// ext.add(1);             // ❌ 编译错误：不确定真实子类型，写入不安全
ext.add(null);             // ✅ 唯一允许写入的值

List<? super Integer> sup = new ArrayList<Number>();
sup.add(10);               // ✅ 写：Integer 一定能放进 Integer 的任意父类型容器
Object o = sup.get(0);     // ✅ 读只能当 Object
// Integer i = sup.get(0); // ❌ 编译错误：容器可能是 List<Object>
```
记忆 **PECS**：Producer（只被读取）用 `? extends`，Consumer（被写入）用 `? super`。

| 集合 | 结构 | 适用场景 | 不适用 / 注意 |
|---|---|---|---|
| `ArrayList` | 动态数组 | 读多写少、随机下标访问 O(1)、单线程遍历 | 频繁在中间插入删除（需搬移 O(n)） |
| `HashMap` | 数组桶 + 链表/红黑树 | 单线程 key-value 查找、去重、计数，平均 O(1) | 多线程并发读写（可能丢数据/结构异常） |
| `ConcurrentHashMap` | CAS + 分段锁（JDK8 后桶级 synchronized） | 多线程共享缓存、并发计数 | 只保证单次操作原子；“查了再改”这类复合操作要用 `computeIfAbsent/merge` |

---

### Q8. 写出接口、多态实现、不可变 String/StringBuilder、自定义异常示例，说明 ==/equals、checked/unchecked 边界。

```java
interface Greeter { String greet(String name); }                 // 能力契约
class EnglishGreeter implements Greeter {
    public String greet(String name) { return "Hello, " + name; }
}
class ChineseGreeter implements Greeter {
    public String greet(String name) { return "你好, " + name; }
}

// checked：继承 Exception（非 RuntimeException），编译器强制处理
class NameTooShortException extends Exception {
    public NameTooShortException(String m) { super(m); }
}
// unchecked：继承 RuntimeException，不强制声明
class EmptyNameException extends RuntimeException {
    public EmptyNameException() { super("name 不能为空"); }
}

public class OopExceptionDemo {
    static String safeGreet(Greeter g, String name) throws NameTooShortException {
        if (name == null || name.isBlank()) throw new EmptyNameException();
        if (name.length() < 2) throw new NameTooShortException("名字太短");
        return g.greet(name);
    }
    public static void main(String[] args) {
        Greeter g = new EnglishGreeter();   // 多态：接口引用指向实现
        System.out.println(g.greet("Tom"));

        String s = "ab"; s.concat("cd");    // 返回新串，s 本身不变
        System.out.println(s);              // ab —— String 不可变
        StringBuilder sb = new StringBuilder("ab");
        sb.append("cd");                    // 原地修改
        System.out.println(sb);             // abcd —— StringBuilder 可变

        String x = new String("hi"), y = new String("hi");
        System.out.println(x == y);         // false：比引用地址
        System.out.println(x.equals(y));    // true：比内容

        try { safeGreet(g, "A"); }
        catch (NameTooShortException e) { System.out.println("checked: " + e.getMessage()); }
    }
}
```
边界：`==` 对基本类型比值、对引用比地址；`equals` 比逻辑内容（String 已重写）。**checked** 用于“可恢复的外部失败”（IO/SQL/业务规则不满足），必须 `throws` 或 `try-catch`；**unchecked** 用于编程缺陷（空值、非法参数、非法状态），不强制处理。

---

### Q9. 写出 Function、Predicate、Stream filter/map/collect 和 Optional.orElseThrow，解释惰性执行与副作用边界。

```java
List<String> raw = List.of("apple", "", "banana", "", "cherry");

Function<String, Integer> len = String::length;          // 转换：T -> R
Predicate<String> nonEmpty = s -> !s.isEmpty();          // 判断：T -> boolean

List<Integer> result = raw.stream()
        .filter(nonEmpty)            // 中间操作，此刻不执行
        .map(String::toUpperCase)    // 中间操作，此刻不执行
        .map(String::length)
        .collect(Collectors.toList());// 终结操作，此刻才真正遍历
System.out.println(result);         // [5, 6, 6]

Optional<String> first = raw.stream().filter(s -> s.length() > 5).findFirst();
String v = first.orElseThrow(() -> new NoSuchElementException("没有长度>5的元素"));
```
**惰性执行**：`filter/map/flatMap/peek/sorted` 等中间操作只拼装流水线，遇到 `collect/forEach/count/findFirst/anyMatch` 等终结操作才驱动数据流动。
**副作用边界**：中间操作必须是纯函数，不要在 `map/filter` 里修改外部集合或写库；需要副作用时放在终结操作 `forEach` 中；并行流下中间操作的副作用会产生数据竞争。`Optional` 表达“可能缺失”的返回值，用 `orElse/orElseThrow/map` 显式处理，禁止无条件 `get()`。

---

### Q10. 写出抽象类和接口的多态调用，指出封装、继承、抽象分别解决什么问题。

```java
interface Swimmable { void swim(); }                       // 接口：能做什么（可多实现）
abstract class Animal {                                    // 抽象类：是什么（可复用代码）
    protected String name;
    Animal(String name) { this.name = name; }
    public abstract void makeSound();                      // 抽象方法：强制子类实现
    public void sleep() { System.out.println(name + " sleeping"); } // 具体方法：复用
}
class Dog extends Animal implements Swimmable {
    Dog(String n) { super(n); }
    public void makeSound() { System.out.println(name + ": 汪汪"); }
    public void swim() { System.out.println(name + ": 狗刨"); }
}
public class AbsDemo {
    public static void main(String[] args) {
        Animal a = new Dog("旺财");   // 多态：父类引用指向子类，运行时动态分派
        a.makeSound(); a.sleep();
        Swimmable s = new Dog("旺财"); s.swim();
    }
}
```
- **封装**解决“状态被外部随意篡改”：字段 private，通过方法受控访问；
- **继承**解决“代码重复”：抽取公共字段/方法到父类复用（Java 单继承）；
- **抽象**解决“调用方绑定具体实现导致紧耦合”：用接口/抽象类定义契约，面向接口编程，替换实现不改调用方。

---

### Q11. 用两个相同内容的字符串验证字符串池、== 和 equals 的差异。

```java
String a = "hello", b = "hello";
System.out.println(a == b);                  // true：字面量进常量池，复用同一对象

String c = new String("hello"), d = new String("hello");
System.out.println(c == d);                  // false：new 强制在堆上创建两个对象
System.out.println(c.equals(d));             // true：内容相同

System.out.println(c.intern() == a);         // true：intern 返回池中对象
String f = "he" + "llo";                     // 编译期常量折叠
System.out.println(f == a);                  // true
String part = "he";
String g = part + "llo";                     // 运行时拼接（底层 StringBuilder）
System.out.println(g == a);                  // false：产生新对象
```
结论：比较字符串内容永远用 `equals`，不要依赖 `==`；字面量/编译期常量入池，`new` 与运行时拼接产生新堆对象。

---

### Q12. 写出 try-with-resources 和自定义业务异常，指出 suppressed exception 的来源。

```java
class BizException extends RuntimeException {                 // 携带稳定错误码的业务异常
    private final String code;
    public BizException(String code, String msg) { super(msg); this.code = code; }
    public String getCode() { return code; }
}
class MyResource implements AutoCloseable {
    private final String name;
    MyResource(String n) { name = n; System.out.println(n + " opened"); }
    public void work() { throw new BizException("WORK_FAIL", "业务失败"); }
    @Override public void close() { throw new RuntimeException(name + " close 失败"); }
}
public class TwrDemo {
    public static void main(String[] args) {
        try (MyResource r = new MyResource("R1")) {
            r.work();    // 抛主异常 BizException
        } catch (BizException e) {
            System.out.println(e.getCode());        // WORK_FAIL（主异常保留）
            for (Throwable t : e.getSuppressed())   // close() 的异常挂在这里
                System.out.println("suppressed: " + t.getMessage());
        }
    }
}
```
**suppressed 来源**：try 块已抛出主异常后，编译器生成的自动 `close()` 调用若再抛异常，不会覆盖主异常，而是通过 `addSuppressed` 附加；多资源按声明逆序关闭，每个关闭异常都会被收集。这避免了“资源关闭失败把真正的业务异常吞掉”。

---

### Q13. 用 `List<? extends Number>` 和 `List<? super Integer>` 写合法读写代码，解释编译器限制。

```java
public class WildcardDemo {
    static <T> void copy(List<? extends T> src, List<? super T> dst) {
        for (T item : src) dst.add(item);   // 源 extends 安全读 T，目标 super 安全写 T
    }
    public static void main(String[] args) {
        List<? extends Number> ext = new ArrayList<>(List.of(1, 2, 3));
        Number n = ext.get(0);     Object o1 = ext.get(0);   // 读：Number/Object 可以
        // ext.add(1); ext.add(1.0);                        // 写具体类型全部禁止
        ext.add(null);                                         // 只允许 null

        List<? super Integer> sup = new ArrayList<Number>();
        sup.add(1); sup.add(2);                               // 写 Integer 合法
        Object o2 = sup.get(0);                               // 读只能当 Object
        // Number x = sup.get(0); Integer y = sup.get(0);    // 更具体类型禁止

        copy(List.of(1, 2, 3), new ArrayList<Number>());      // PECS 组合
    }
}
```
**编译器限制的本质**：通配符是“未知类型”。`? extends Number` 不知道到底是 Integer 还是 Double，写入任何具体类型都可能破坏容器真实类型，所以禁止写；`? super Integer` 不知道是 Number 还是 Object，读出的类型无法确定，所以只能当 Object 读，而写入 Integer（及其子类）对任何父类型容器都安全。

---

## External

### E1. 删除一个 starter 后运行构建，记录缺失类型和恢复位置。

**操作与结论**：例如注释掉 `spring-boot-starter-web` 后执行 `mvn -B compile`：
- 缺失类型/注解：`@RestController、@RequestMapping、@PostMapping、@RequestBody`（包 `org.springframework.web.bind.annotation`）、`ResponseEntity` 等全部无法解析，编译报 `cannot find symbol`；运行期还会缺失内嵌 Tomcat，应用无法启动 HTTP 服务。
- 恢复位置：`pom.xml` 的 `<dependencies>` 内重新加回该 starter，刷新 Maven（IDEA 右上角 Reload / `mvn -U compile`）即可。
- 规律：starter 是“一组依赖的聚合”，删 starter = 同时删掉它传递引入的所有包；根据报错的包名反查属于哪个 starter。

### E2. 用 `--spring.profiles.active=test` 启动，确认实际使用 H2 而非 MySQL。

**结论与验证点**：
- 测试侧 `src/test/resources/application-test.yml` 配置 `jdbc:h2:mem:question_bank;MODE=MySQL`，驱动 `org.h2.Driver`；集成测试类上的 `@ActiveProfiles("test")` 等价于激活该 profile。
- 命令行激活：`mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=test`（或 jar 启动 `java -jar xxx.jar --spring.profiles.active=test`）。
- 确认方式：启动日志中 `HikariCP` 打印的 `jdbcUrl` 为 `jdbc:h2:mem:...` 即说明走 H2；H2 是内存库，进程结束数据消失，且不依赖 docker-compose 的 MySQL 3307 端口。

### E3. 解释为什么 `target/` 不应提交到 Git。

`target/` 是 Maven 的**构建产物目录**（class、JAR、surefire 报告均由源码随时重新生成），提交它会：① 产生大量无意义 diff 和仓库膨胀；② 不同机器/ JDK 生成的 class 不一致引发冲突；③ 旧 JAR 被误部署。正确做法是在 `.gitignore` 中加入 `/target/`，仓库只保留 `pom.xml` 与 `src/` 这些“源”，产物需要时由 CI 重新构建。
