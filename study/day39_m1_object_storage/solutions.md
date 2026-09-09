# Day39 M1 Object Storage · 题目与标准解答（Solutions）

> 依据：`study.md`（S3/MinIO 对象存储、预签名 URL、双系统一致性补偿）。当前 M0 无对象存储依赖，`ImportJob.sourceName` 只是输入标识，不是 bucket/key。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
当前事实：项目无 S3/MinIO 适配器；目标设计锚点：AWS S3Presigner / MinIO Java SDK，业务元数据仍在 MySQL。

---

### Q2. 设计 PDF / 解析结果 key。
- 原始 PDF：`import/{ownerId}/{yyyy}/{mm}/{dd}/{uuid}.pdf`；
- 解析结果（文本/结构化 JSON）：`import/{ownerId}/{jobId}/parsed.json`；
- key 由服务端用 UUID 生成、与原始文件名解耦；同一内容可用 contentHash 去重；bucket 按环境隔离（dev/prod），对象不可由客户端自选 key。

---

### Q3. 写对象不存在 / 权限测试。
- 对象不存在：GET/HEAD 返回 404，任务状态标记失败并给出公开错误，不把底层异常透给客户端；
- 无权限用户下载他人对象：服务端先校验 owner/任务归属，不通过直接 403（根本不签发 URL）；
- 预签名 URL 过期后访问：对象存储返回 403，需要重新申请；
- bucket/凭据错误：服务端记录内部错误，返回 500 统一契约。

---

### Q4. 写出 bucket/key、ETag、content hash、预签名 URL、补偿状态语义。
- **bucket + key**：对象存储中对象的全局定位（桶 + 对象键），对象不可变覆盖写优先；
- **ETag**：存储服务返回的完整性/版本线索（S3 单段上传时是 MD5，多段上传不是纯 MD5，不能当强校验）；
- **content hash**：应用自己算的 SHA-256，用于端到端完整性校验与去重，比 ETag 更可靠；
- **预签名 URL**：服务端用密钥对“对象 + 操作(GET/PUT) + 过期时间”签名生成的**短期**授权链接，到期失效，不是永久公开地址；
- **补偿状态**：跨对象存储与数据库两个系统，用 `PENDING/AVAILABLE/DELETE_PENDING` 等状态表达并最终一致。

---

### Q5. 写出预签名下载的对象、方法、过期时间、用户范围字段。
授权数据结构至少包含：
```java
record DownloadGrant(String objectKey, String method /*GET*/,
                     Instant expiresAt, String userScope /*ownerId/角色*/) {}
```
流程：客户端请求下载 → 服务端校验该用户对任务/对象的授权 → 用 S3Presigner 生成 `GetObjectPresignRequest`（指定 bucket、key、短 TTL，如 5 分钟）→ 返回 URL；客户端凭 URL 直连对象存储下载。bucket 名、内部 key、访问凭据绝不下发给不可信客户端。

---

### Q6. 设计“对象成功/数据库失败”与“数据库成功/对象失败”的补偿。
| 情况 | 结果 | 补偿 |
|---|---|---|
| 对象已上传，但 DB 记录失败 | **孤儿对象**（存储里有、系统无记录） | DB 记录先置 PENDING；清理任务扫描“超过保留期仍无 DB 引用”的对象，幂等删除并审计 |
| DB 记录成功，但对象上传失败 | **悬空引用**（DB 指向不存在对象） | 状态保持 PENDING/FAILED，可重新上传；只有对象 HEAD 校验通过才置 AVAILABLE |
推荐顺序：先上传对象到临时区/PENDING → 再在 DB 事务写元数据（置 AVAILABLE 前做 HEAD 确认）；删除走“DB 标记 DELETE_PENDING → 异步删对象 → 删 DB 记录”的两阶段，删除幂等、带保留期与审计日志。

---

## External

### E1. 用 MinIO 完成 put/get/delete。
本地起 MinIO 容器，用 S3 兼容 SDK：`putObject` 上传并取回 ETag、应用侧算 SHA-256 入库；`getObject`/预签名 GET 下载验证内容字节一致；`deleteObject` 删除并验证幂等（删第二次不报错）。验证短期 URL 过期后拒绝访问。

### E2. 设计清理扫描。
定时任务分两类：① 孤儿对象：列举存储对象，与 DB 元数据比对，超过保留期无引用则删除；② 悬空/卡住记录：扫描长期 PENDING 的 DB 记录，重新确认对象是否存在，不存在则置 FAILED 或重新上传。所有清理动作幂等、分批小步执行、写审计日志，避免误删仍在用的对象。
