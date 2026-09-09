# Day36 M1 File Upload · 题目与标准解答（Solutions）

> 依据：`study.md`；注意当前项目 `POST /api/import-jobs` 只接收 JSON `sourceName`，**尚未接入** MultipartFile/真实 PDF/对象存储，以下含“当前事实”与“目标设计”两部分。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
当前事实锚点：`ImportJobController` 的 `POST /api/import-jobs` 只接收 JSON sourceName 并建持久任务（RECEIVED）。目标设计锚点：Spring `MultipartFile`、OWASP File Upload Cheat Sheet。

---

### Q2. 设计导入记录。
文件元数据表（对应一条上传）：`id、owner_id、original_name(仅展示)、storage_key(随机)、size、content_type、content_hash(SHA-256)、status、error、created_at`，关联 import_job。状态机 `RECEIVED → STORED（已落对象存储）→ PARSING → IMPORTED/FAILED`，每次转换带 owner 与任务 id，禁止客户端直接指定状态。

---

### Q3. 列出 `..` / 超大 / 错误 MIME 测试。
| 攻击/异常输入 | 期望 |
|---|---|
| 文件名为 `../../etc/passwd.pdf` | 原始文件名不参与存储路径，拒绝路径穿越，用随机 key |
| 文件大小超过上限（如 20MB） | 在 multipart 解析阶段/服务端校验直接 400/413，不写盘 |
| 扩展名 pdf 但内容是 exe / 声明 MIME 与 magic bytes 不符 | 以 magic bytes 实际判定，拒绝或标记 FAILED |
| 伪造 Content-Type | 不信任客户端声明，服务端嗅探真实类型 |
| 超页数/超深嵌套/解析超时 | 限制页数、解析时间、内存，超时即 FAILED |
| 高并发上传 | 限制每用户并发数 |

---

### Q4. 写出 multipart 限制、随机 storage key、路径规范化和元数据字段。
- **multipart 限制**：`spring.servlet.multipart.max-file-size/max-request-size`，服务端再校验 size/MIME/页数/解析时长/并发；
- **随机 storage key**：`UUID/哈希 + 日期分区`，如 `import/2026/09/05/<uuid>.pdf`，与原始文件名解耦；
- **路径规范化**：服务端基于固定根目录 + 随机 key 拼路径，做 normalize 后确认仍在根目录内，拒绝任何 `..`、绝对路径、空字节；原始文件名只做展示并清洗；
- **元数据字段**：owner、size、contentType、contentHash（SHA-256，校验完整性与去重）、storageKey、状态、创建时间、解析错误。

---

### Q5. 写出 MultipartFile Controller 方法和文件状态机。
```java
@PostMapping(value = "/api/import-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<ImportView> upload(@RequestPart("file") MultipartFile file,
                                         @AuthenticationPrincipal AuthPrincipal user) throws Exception {
    ImportJob job = importService.receive(user.userId(), file); // 内部校验大小/类型/hash/落存储/建任务
    return ResponseEntity.accepted()
            .location(URI.create("/api/import-jobs/" + job.getId()))
            .body(ImportView.from(job));
}
```
状态机：`RECEIVED（请求到达）→ STORED（字节安全落盘/对象存储，算出 hash）→ PARSING（worker 解析）→ IMPORTED（生成候选）/ FAILED（带公开错误）`。

---

### Q6. 构造扩展名/MIME/magic bytes 不一致的安全边界。
三者来源可信度不同：扩展名可随意改、客户端声明 MIME 可伪造、只有 **magic bytes（文件头魔数）**反映真实内容。安全策略采用 allow-list：
1. 先看大小；
2. 读前若干字节嗅探真实类型（如 PDF 魔数 `%PDF-`）；
3. 真实类型必须在白名单（仅 PDF）；扩展名/声明 MIME 与真实类型不一致时拒绝或置 FAILED；
4. 用隔离、限资源的解析器，禁用外部实体/脚本，防止解析器漏洞造成 CPU/内存耗尽甚至 RCE。
文件一律视为**恶意输入**，上传目录不可执行、不与应用同域直接暴露。

---

## External

### E1. 验证临时目录穿越。
测试把 multipart 临时目录、目标存储目录与恶意文件名组合，确认：服务端从不使用原始文件名拼物理路径，normalize + 根目录 containment 校验能拦截 `..`；临时文件用完即清理，不残留在 web 可访问目录。

### E2. 比较 MinIO / S3。
- **S3（AWS）**：托管对象存储，11 个 9 持久性、多 AZ、预签名 URL、版本与生命周期策略，按量付费、需公网/专线；
- **MinIO**：S3 兼容的自建对象存储，API/SDK 基本一致，适合本地开发与私有部署、数据自控；
- 二者都以 bucket+key 定位对象，业务代码用同一 S3 SDK 抽象，通过 endpoint 切换；教学/内网用 MinIO，上生产用 S3（或兼容服务），元数据与状态仍保存在 MySQL。
