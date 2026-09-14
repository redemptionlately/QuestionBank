# MustRemember

- Entity 映射的基本骨架是（真实 PaperVersion 的 status 是字符串，不是枚举；枚举映射见 PracticeSession 的 PracticeStatus）：
  ```java
  @Entity
  @Table(name = "paper_version", uniqueConstraints = @UniqueConstraint(
      name = "uk_paper_version_no", columnNames = {"bank_id", "version_no"}))
  public class PaperVersion {
      @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
      private Long id;
      @Column(nullable = false, length = 20)
      private String status = "DRAFT";   // publish() 中改为 "PUBLISHED"
  }
  ```
- 主键保证身份，外键保证引用，联合唯一键保证 `(bank_id, version_no)` 不重复，`NOT NULL` 保证必需字段完整
- Flyway 文件名遵循 `V1__m0_schema.sql`，现有迁移 V1~V4（V1 七表、V2 索引、V3 import_job、V4 outbox 两表，共 11 张表）；执行记录包含版本、描述和 checksum；`spring.jpa.hibernate.ddl-auto=validate` 只校验映射，不创建结构
- Java I/O 用字节流处理二进制、字符流处理文本；NIO `Path/Files/ByteBuffer/Channel` 支持路径操作和更明确的资源边界，文件大小和编码必须显式处理
- Java 序列化不应默认使用原生 `ObjectInputStream` 处理不可信输入；JSON 映射要定义字段兼容、未知字段、时区和数值精度
- 源码索引（MustRemember）：[PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 6-32 行的 Entity/字段映射与 publish() 状态迁移；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 21-47 行的 paper/question 表结构

# MustUnderstand

- Persistence Context 中 transient 未被管理，managed 被脏检查跟踪，detached 脱离上下文，removed 标记删除；flush 同步 SQL，事务提交决定其他事务可见性
- 历史迁移内容改变会导致 checksum 失败；结构变更使用新的版本迁移，不能覆盖已经执行的版本
- 源码索引（MustUnderstand）：[PracticeSession.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSession.java) 第 8-19 行的实体字段、枚举映射与第 19 行 `@Version`；[V1__m0_schema.sql](../../app/src/main/resources/db/migration/V1__m0_schema.sql) 第 49-86 行的练习/错题外键与唯一键；V3/V4 迁移在 [V3__async_import_jobs.sql](../../app/src/main/resources/db/migration/V3__async_import_jobs.sql) 与 [V4__outbox_events.sql](../../app/src/main/resources/db/migration/V4__outbox_events.sql)
- 外部源码索引（MustRemember）：[Files API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html) 的 `readString/writeString/newBufferedReader`；[Jackson ObjectMapper](https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/latest/com/fasterxml/jackson/databind/ObjectMapper.html) 的 JSON 映射
- 外部源码索引（MustUnderstand）：[Java Serialization Filtering](https://docs.oracle.com/en/java/javase/21/core/serialization-filtering1.html) 的不可信输入风险
