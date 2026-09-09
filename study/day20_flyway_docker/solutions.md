# Day20 Flyway & Docker · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 V1/V2 迁移、`docker-compose.yml`、`application.yml`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：V1 1-86 行建表顺序；V2 2-9 行三个查询索引；compose 的 volume/healthcheck；application.yml 的 datasource/Flyway/JPA 启动顺序。

---

### Q2. 确认 V1/V2 success=1。
应用启动时 Flyway 按版本号顺序执行迁移，并在 `flyway_schema_history` 每个成功版本写一行（success=1）。验证：
```sql
SELECT installed_rank, version, description, success, checksum
FROM flyway_schema_history ORDER BY installed_rank;
-- 1 | 1 | m0 schema         | 1 | <checksum>
-- 2 | 2 | m0 query indexes  | 1 | <checksum>
```
随后 Hibernate `ddl-auto=validate` 校验实体与列一致；任一迁移失败对应行 success=0，应用启动中止，需要修复后才能继续。

---

### Q3. 重启验证数据。
第二次启动 Flyway 发现 V1/V2 已在 history 且 checksum 匹配 → 跳过，不重复执行；命名 volume 中的业务数据（题库、已发布版本、提交结果）原样保留。验证：重启后 `SELECT COUNT(*) FROM question_bank;` 与重启前一致。

---

### Q4. 写出追加 V3 迁移、validate、Compose volume、端口映射配置。

**追加新迁移（只新增，不改旧文件）**：`src/main/resources/db/migration/V3__async_import_jobs.sql`
```sql
CREATE TABLE import_job (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ...
);
```
**JPA 只校验**：
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    validate-on-migrate: true
```
**Compose volume 与端口（真实配置）**：
```yaml
services:
  mysql:
    image: mysql:8.4
    ports:
      - "127.0.0.1:3307:3306"        # 宿主 3307 → 容器 3306，只绑定回环地址
    volumes:
      - question_bank_mysql:/var/lib/mysql   # 数据独立于容器生命周期
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uquestion_bank", "-pquestion_bank"]
volumes:
  question_bank_mysql:
    name: question-bank-m0_question_bank_mysql
    external: true
```
四概念：image 不可变模板、container 运行实例、volume 独立持久状态、port 宿主↔容器映射。

---

### Q5. 写出 V2 索引 SQL，并说明 H2 与真实 MySQL 的验证边界。

真实 V2：
```sql
CREATE INDEX idx_paper_version_status_published_at
    ON paper_version (status, published_at);                 -- 已发布列表：按状态过滤+时间排序
CREATE INDEX idx_practice_session_student_created_at
    ON practice_session (student_id, created_at);            -- 学生会话列表
CREATE INDEX idx_wrong_question_student_last_wrong_at
    ON wrong_question (student_id, last_wrong_at);           -- 错题本列表
```
索引列顺序匹配真实读路径（等值过滤列在前、排序列在后，遵循最左前缀）。
**验证边界**：测试用 H2 `MODE=MySQL`，它只模拟 MySQL 语法与部分行为，**不等于** InnoDB 的锁机制、索引选择和优化器。H2 能验证“迁移能执行、索引存在、功能正确”；但“索引是否被选中、执行计划是否最优、锁等待/间隙锁表现”必须在真实 MySQL 8.4 上用 `EXPLAIN` 验证。

---

## External

### E1. 改旧迁移复现 checksum 错误。
修改已执行过的 V1 文件内容后重启，Flyway 比对 `flyway_schema_history.checksum` 不一致，启动失败并报 `Migration checksum mismatch`。正确做法：已发布迁移不可改，结构演进只追加新版本（V4、V5…）；仅本地/测试内存库可用 `flyway clean` 后重来。

### E2. 设计 V3 兼容发布（多阶段）。
以“给 question_bank 增加非空分类 category”为例的扩展-收缩（expand/contract）发布：
1. **扩展阶段**：V3 先加**可空**列 `category VARCHAR(50) NULL`（旧代码不写也不报错），部署新代码双写/默认值；
2. **回填阶段**：离线/分批 `UPDATE` 把历史行填上默认分类；
3. **收紧阶段**：确认无 NULL 后，V4 再 `ALTER TABLE ... MODIFY category VARCHAR(50) NOT NULL`，最后删除旧代码路径。
删除列/重命名列同理要分多个版本灰度，避免“新代码 + 旧库”或“旧代码 + 新库”瞬间不兼容。
