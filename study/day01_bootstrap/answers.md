# Answers

## Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。
- 写出一套完整的 `QuestionBank` 三层代码：Entity（主键、列约束、JPA 无参构造器）、Repository（`JpaRepository`）、Service（构造器注入、参数校验、`save` 和读方法的异常边界、读写事务边界）、Controller（`@RequestMapping`、`@PostMapping`、`@RequestBody`、DTO 和响应投影）；不能只写方法签名。`findById`/`@GetMapping` 的完整查询路径在后续 M0 天继续展开。

```java
@Entity
@Table(name = "question_bank")
public class QuestionBank{
@Id @GeneratedValue(strategy = Generation.IDENTITY)
private Long Id;
@Column(nullable = false, length = 160)
private String name;

protected QuestionBank() {};
public QuestionBank(Long Id, String name) {
    this.Id = Id, this.name = name;
}

public Long getId(Long Id) {this.Id = Id};
public String getName(String name) {this.name = name};
}

package com.allen.QuestionBankRepository.banks;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {};

@Service
public class BankService{
    private final QuestionBankRepository banks;
    public BankService(QuestionBankRepository banks) {
        this.banks = banks;
    };
    @Transactional
    QuestionBank createbanks(ApiTokenFilter.AuthoPrincipal user, String name) {
        if (name == null || name.IsBlank()) throw bad("名称不能为空");
        return banks.save(new QuestionBank(user.userId(), name.Trim()));
    }
}

@RestController
@RequestMapping("/api")
public class BankController {
    private final BankService banks;
    public BankController(BankService service) {
        this.service = service;
    }

    @PostMapping("/allen/admin")
    @PreAuthorize(hasRole("Admin"))
    public BankResponse createBanks(@valid @RequestBody CreateBankRequest request, String name) {
        QuestionBank banks = service.createBanks(CurrentUser.repuire, require.name)
        return new BankResponse(bank.getId(), bank.getName());
    }

    public Record CreateBankRequest(@NotBlank @Size(max = 160) String name) {};
    public Record BankResponse (String name) {};
}




```

- 说明 `mvn test`、`mvn package`、`mvn spring-boot:run` 的输入、输出和副作用。

- 指出 `pom.xml` 中运行时依赖和测试依赖，并解释 `runtime` 与 `test` scope 的区别。
运行时依赖时mysql 测试依赖是test,h2database,security 区别是一个是运行时依赖一个是测试时依赖
- 运行 `mvn test`，解释退出码、测试数量和首个失败原因。

- 写出 Java 方法按值传递、`final` 引用和 `equals/hashCode` 契约的最小示例。
- 写出泛型 `? extends` 与 `? super` 的读写示例，并说明 `ArrayList`、`HashMap`、`ConcurrentHashMap` 的适用场景。
- 写出一个接口、多态实现、不可变 `String`/`StringBuilder` 和自定义异常示例，并说明 `==`/`equals`、checked/unchecked exception 的边界。
- 写出 `Function`、`Predicate`、Stream 的 `filter/map/collect` 和 `Optional.orElseThrow` 示例，并解释惰性执行与副作用边界。
- 写出一个抽象类和接口的多态调用，并指出封装、继承、抽象分别解决什么问题。
- 用两个相同内容的字符串验证字符串池、`==` 和 `equals` 的差异。
- 写出 `try-with-resources` 和自定义业务异常，指出 suppressed exception 的来源。
- 用 `List<? extends Number>` 和 `List<? super Integer>` 写出合法读写代码，解释编译器限制。

## External

 删除一个 starter 后运行构建，记录缺失类型和恢复位置。
- 用 `--spring.profiles.active=test` 启动，确认实际使用 H2 而非 MySQL。
- 解释为什么 `target/` 不应提交到 Git。