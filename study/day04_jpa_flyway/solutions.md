# Day04 JPA & Flyway · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实源码实体类与 `V1__m0_schema.sql`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`PaperVersion.java` 6-17 行实体映射；`V1__m0_schema.sql` 21-47 行 paper/question 表、49-86 行练习/错题外键与唯一键；`PracticeSession.java` 的 `@Version`。

---

### Q2. 写出 `@Entity`、`@Table`、`@Id`、`@GeneratedValue`、`@Column`。

**解答（真实实体示例）**：
```java
@Entity                                                       // 声明为 JPA 实体，对应一张表
@Table(name = "paper_version",
       uniqueConstraints = @UniqueConstraint(                // 联合唯一约束
           name = "uk_paper_version_no", columnNames = {"bank_id", "version_no"}))
public class PaperVersion {
    @Id                                                       // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)      // 数据库自增
    private Long id;

    @Column(name = "bank_id", nullable = false) private Long bankId;
    @Column(nullable = false, length = 200) private String title;
    @Enumerated(EnumType.STRING)                              // 枚举以字符串存储（不用序号）
    @Column(name = "question_type", nullable = false, length = 20)
    private QuestionType questionType;
    @Column(nullable = false, columnDefinition = "TEXT") private String prompt;
}
```
注解职责：`@Entity` 标记实体；`@Table` 指定表名/唯一约束；`@Id` 标主键；`@GeneratedValue(IDENTITY)` 依赖数据库自增；`@Column` 声明列名、非空、长度、列定义；字段名驼峰默认映射下划线列（也可用 `name` 显式指定）。

---

### Q3. 解释主键、外键、唯一约束、状态字段和 `@Version`。

- **主键 PRIMARY KEY**：唯一标识一行，本项目统一 `BIGINT AUTO_INCREMENT`；保证实体身份。
- **外键 FOREIGN KEY**：保证引用完整性，例如 `paper_version.bank_id → question_bank.id`，不能插入不存在的题库，父行被引用时不能随意删除。
- **唯一约束 UNIQUE**：保证业务唯一性，允许 NULL（MySQL 中 NULL 不参与唯一性比较）。本项目有：
  - `uk_user_account_username(username)`
  - `uk_paper_version_no(bank_id, version_no)`：同一题库版本号不重复
  - `uk_question_version_no(paper_version_id, question_no)`：同卷题号不重复
  - `uk_submission_question(session_id, question_version_id)`：同一道题在一个会话只有一条答案
  - `uk_wrong_question(student_id, question_version_id)`
- **状态字段**：用字符串/枚举表达状态机（`DRAFT/PUBLISHED`、`IN_PROGRESS/SUBMITTED`），状态流转必须在实体内聚方法中完成（如 `publish()`、`submit()`），而不是让客户端直接 set。
- **`@Version`**：JPA 乐观锁。实体更新时 `WHERE id=? AND entity_version=?`，版本不匹配抛 `ObjectOptimisticLockingFailureException`，用于并发更新不互相覆盖；`PracticeSession` 上的 `@Version private long entityVersion;` 配合提交时的行锁使用。

---

### Q4. 在 H2 测试库执行 Flyway V1，并让 `ddl-auto=validate` 通过。

**解答（本项目真实测试配置 `application-test.yml`）**：
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:question_bank;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: validate     # 只校验实体与表结构是否一致，不建表/不改表
  flyway:
    enabled: true            # 由 Flyway 执行 V1/V2/V3 建表
```
执行链：测试启动 Spring 上下文 → Flyway 按版本号顺序执行 `db/migration/V*.sql` 并在 `flyway_schema_history` 记录 version/checksum → Hibernate `validate` 比对实体字段与列（名、类型、非空），一致才启动成功。validate 失败常见原因：实体多了字段但迁移没建列、枚举列长度不够。

---

### Q5. 验证重复用户名、重复题号、重复会话题目被数据库拒绝。

均由唯一约束在**数据库最后防线**拒绝，应用并发下也无法插入重复行：

| 重复场景 | 触发的约束 | 重复插入结果 |
|---|---|---|
| 相同 username | `uk_user_account_username` | 抛 `DataIntegrityViolationException` |
| 同卷相同 question_no | `uk_question_version_no` | 同上 |
| 同会话同题重复答案行 | `uk_submission_question` | 同上（业务上先查后改为 UPDATE 同一行，见 Day07） |

注意应用层“先查 max 再 +1 版本号”在并发下仍可能撞车，最终靠唯一键兜底，捕获后可重试。

---

### Q6. 写出 `Path/Files` 文本读写和 try-with-resources 字节流示例，说明 JSON 字段兼容、编码和不可信反序列化风险。

```java
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;

// 字符内容：NIO Files，显式指定 UTF-8，避免依赖平台默认编码
Path path = Path.of("data", "sample.txt");
Files.writeString(path, "你好", StandardCharsets.UTF_8);
String text = Files.readString(path, StandardChars.UTF_8);

// 二进制：字节流 + try-with-resources 自动关闭
try (InputStream in = Files.newInputStream(path);
     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    byte[] data = out.toByteArray();
}
```
**JSON 字段兼容**：新增可空字段向后兼容（老数据读出为 null）；删除字段默认忽略；时间统一用 ISO-8601（`Instant`）；大整数用字符串或 Long 避免 JS number 精度问题；未知字段默认忽略。
**编码**：读写文本一律显式 `StandardCharsets.UTF_8`，数据库连接也指定 utf8mb4。
**不可信反序列化风险**：不要对外部输入使用 Java 原生 `ObjectInputStream`（可触发任意类 gadget 链导致 RCE）；外部数据只走 JSON（Jackson）并定义明确 DTO；必要时配置 JEP 290 序列化过滤器。

---

## External

### E1. 修改已执行迁移文件后启动，记录 Flyway checksum 错误。
Flyway 对每个已执行版本在 `flyway_schema_history` 保存 checksum。改动 V1 文件内容后重启，校验不通过，抛 `FlywayValidateError: Migration checksum mismatch`。正确做法：**已发布迁移不可改**，结构变更新增 `V4__xxx.sql`；仅本地未提交的迁移才允许修改并用 `flyway clean`（测试内存库可随意）重来。

### E2. 删除外键后运行跨用户测试，区分应用防线与数据库防线。
- **应用防线**：Controller 的 `@PreAuthorize` 角色校验 + Service 的 `ownerId/studentId` 归属判断，处理正常非法请求并给出 403/400；
- **数据库防线**：外键、非空、唯一约束，防的是绕过应用（脏数据、并发、bug、直接连库写入）。
删掉外键后，应用测试可能仍通过（说明应用层拦住了），但数据库不再能阻止孤儿行——证明两道防线职责不同、缺一不可。

### E3. 使用 MySQL Compose 启动后再次执行迁移，确认不会重复创建表。
`docker compose up -d mysql`（映射 127.0.0.1:3307）后首次启动执行迁移并写入 `flyway_schema_history`；第二次启动 Flyway 发现 V1/V2/V3 都已记录且 checksum 一致，**跳过已应用版本**，不会重复 `CREATE TABLE`。这正是迁移可重复执行（幂等）的机制。
