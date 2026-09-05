# MustRemember

- 启动类的完整骨架是：
  ```java
  @SpringBootApplication
  public class QuestionBankApplication {
      public static void main(String[] args) {
          SpringApplication.run(QuestionBankApplication.class, args);
      }
  }
  ```
- Maven 的 `compile` 只编译主源码，`test` 进入测试生命周期，`package` 在测试后生成 JAR；依赖由 `pom.xml` 的坐标、scope 和 parent 版本管理
- Java 方法参数按值传递；对象变量保存引用值，`final` 只限制变量再次赋值；`equals` 相等对象必须拥有相同 `hashCode`
- 泛型在编译期进行类型检查并经类型擦除实现；`List<? extends T>` 只能安全读取，`List<? super T>` 能安全写入 `T`
- 面向对象的四个核心概念是封装、继承、多态和抽象；接口表达能力契约，抽象类复用部分实现；依赖应面向接口而不是具体实现
- `String` 不可变且字面量可能进入字符串常量池；大量拼接使用 `StringBuilder`；`==` 比较引用或基本值，`equals` 比较逻辑相等
- 异常按 `Throwable -> Error/Exception -> RuntimeException` 分层；资源释放使用 `try-with-resources`，业务异常应携带稳定错误语义，不能吞掉原始原因
- Lambda 只能捕获 effectively final 的局部变量；`Function/Predicate/Consumer/Supplier` 分别表达转换、判断、消费和无参提供
- Stream 的中间操作惰性执行，终结操作触发遍历；`map` 转换元素、`filter` 筛选、`flatMap` 展平、`reduce/collect` 聚合；并行流不自动适合有副作用的业务写入
- `Optional` 用于表达可能缺失的返回值；不要把它当作实体字段或参数容器，也不要无条件 `get()`；缺失应明确 `orElse`、`orElseThrow` 或分支语义
- 三层最小骨架必须能独立写出，并且每一层的输入、输出和失败边界完整：
  ```java
  // 为便于背写，以下片段省略 package；实际项目还需要这些 import：
  // jakarta.persistence.*、java.time.Instant、org.springframework.stereotype.Service、
  // org.springframework.transaction.annotation.Transactional、org.springframework.web.bind.annotation.*、
  // org.springframework.security.access.prepost.PreAuthorize、jakarta.validation.*、
  // com.allen.questionbank.auth.ApiTokenFilter.AuthPrincipal

  // QuestionBank.java：持久化实体，独立文件
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

      protected QuestionBank() {}              // JPA 需要
      public QuestionBank(Long ownerId, String name, String description) {
          this.ownerId = ownerId;
          this.name = name;
          this.description = description;
          this.createdAt = Instant.now();
      }
      @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
      public Long getId() { return id; }
      public Long getOwnerId() { return ownerId; }
      public String getName() { return name; }
      public String getDescription() { return description; }
  }

  // QuestionBankRepository.java：只表达持久化查询
  public interface QuestionBankRepository
          extends JpaRepository<QuestionBank, Long> {}

  // BankService.java：业务校验、实体创建、异常语义
  @Service
  public class BankService {
      private final QuestionBankRepository banks;

      public BankService(QuestionBankRepository banks) {
          this.banks = banks;
      }

      @Transactional
      public QuestionBank createBank(ApiTokenFilter.AuthPrincipal user, String name, String description) {
          if (user == null || name == null || name.isBlank()) {
              throw new IllegalArgumentException("user/name invalid");
          }
          return banks.save(new QuestionBank(user.userId(), name.trim(), description));
      }
  }

  // BankController.java：HTTP 绑定和 DTO 投影
  // import com.allen.questionbank.common.CurrentUser;
  @RestController
  @RequestMapping("/api")
  public class BankController {
      private final BankService service;

      public BankController(BankService service) {
          this.service = service;
      }

      @PostMapping("/admin/banks")
      @PreAuthorize("hasRole('ADMIN')")
      public BankResponse createBank(@Valid @RequestBody CreateBankRequest request) {
          QuestionBank bank = service.createBank(CurrentUser.require(), request.name(), request.description());
          return new BankResponse(bank.getId(), bank.getName(), bank.getDescription());
      }

      public record CreateBankRequest(@NotBlank @Size(max = 160) String name,
                                      @Size(max = 500) String description) {}
      public record BankResponse(Long id, String name, String description) {}
  }
  ```
- 完整请求数据流是：JSON 请求 -> Controller 参数绑定/校验 -> Service 当前用户与业务规则 -> Repository `save/findById` -> 数据库 -> Service 返回实体 -> Controller 转成 DTO -> JSON 响应；找不到数据、越权和非法状态不能伪装成成功响应
- 源码索引（MustRemember）：[QuestionBankApplication.java](../../src/main/java/com/allen/questionbank/QuestionBankApplication.java) 第 7-12 行的启动类；[QuestionBank.java](../../src/main/java/com/allen/questionbank/entity/QuestionBank.java) 第 6-33 行的 Entity、字段、JPA 无参构造器和业务构造器；[QuestionBankRepository.java](../../src/main/java/com/allen/questionbank/repository/QuestionBankRepository.java) 第 1-6 行的 `JpaRepository` 声明；[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 17-35 行的构造器注入、校验、实体创建和 `save`
- 源码索引（MustRemember）：[BankController.java](../../src/main/java/com/allen/questionbank/controller/BankController.java) 第 15-26 行的路由、权限和创建入口；[BankController.java](../../src/main/java/com/allen/questionbank/controller/BankController.java) 第 60-68 行的 DTO 和响应投影；[pom.xml](../../pom.xml) 第 7-12 行的 parent；[pom.xml](../../pom.xml) 第 24-83 行的 dependencies 和插件

# MustUnderstand

- 构造器注入使依赖成为对象不变量；Controller 处理 HTTP，Service 维护业务不变量，Repository 负责持久化查询
- `ArrayList` 的随机访问是 O(1)，`HashMap` 通过 hash 分桶，`ConcurrentHashMap` 保证并发容器操作但不自动保证复合业务操作原子性
- 源码索引（MustUnderstand）：[QuestionBankApplication.java](../../src/main/java/com/allen/questionbank/QuestionBankApplication.java) 第 7-12 行的 `SpringApplication.run` 到 ApplicationContext；[BankController.java](../../src/main/java/com/allen/questionbank/controller/BankController.java) 第 22-26 行的 HTTP 输入、认证主体、Service 调用和 DTO 输出；[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 32-35 行的事务、业务校验和持久化入口；[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 105-108 行的异常语义；[pom.xml](../../pom.xml) 第 24-74 行的 starter、runtime 与 test scope 依赖边界
- 外部源码索引（MustRemember）：[java.util.function](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/package-summary.html) 的四类函数式接口；[Stream API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html) 的 `map/filter/flatMap/collect`
- 外部源码索引（MustUnderstand）：[Optional API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html) 的缺失语义和异常边界
