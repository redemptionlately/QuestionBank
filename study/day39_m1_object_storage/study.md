# MustRemember

- 对象由 `bucket + key` 定位；ETag 是存储服务返回的完整性线索（多段上传非纯 MD5），content hash（SHA-256）用于端到端校验和去重，业务元数据仍在 MySQL
- 预签名 URL 携带对象、操作、权限和过期时间；它是短期授权，不是永久公开地址
- 上传、删除和数据库更新跨越两个系统，可能出现孤儿对象或悬空引用，需要 `PENDING/AVAILABLE/DELETE_PENDING` 等补偿状态（新增状态，不与现有 ImportJobStatus 四值混用）
- 下载链路仍先检查用户/任务授权，再返回短期 URL；bucket 名、内部 key 和凭据不应暴露给不可信客户端
- 预签名下载的授权数据结构至少包含 `objectKey`、`method`、`expiresAt`、`userScope`；服务端签名而非客户端自选 key
- **当前事实**：app/pom.xml 21 项依赖中无任何 S3/MinIO SDK，无 bucket 配置、无 Multipart 字节链路；[ImportJob.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJob.java) 的 sourceName 只是 VARCHAR(200) 文本标识，不是 bucket/key；对象存储全部为设计态
- 可参照的已实现骨架：[ImportJobRetrySweeper.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java)（定时扫描+退避+幂等推进+空候选短路）与 [OutboxRelay.java](../../app/src/main/java/com/allen/questionbank/event/OutboxRelay.java)（扫 PENDING、成功/失败/达上限三分支）；外部动作放在事务 afterCommit（ImportJobService:27）
- 外部源码索引（MustRemember）：[AWS SDK Java S3 presigner](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html) 的 `GetObjectPresignRequest`

# MustUnderstand

- 对象存储与数据库有不同可见性和删除语义；孤儿清理需要保留期、幂等删除和审计日志；"先 DB 提交、afterCommit 再动外部系统"是本项目已验证的时序原则，反向顺序会在事务回滚时留下外部垃圾
- 确定性键去重思想在项目内已有多处真实实例：uk_outbox_dedup、uk_consumed_event_key、uk_submission_question；content hash 去重是同一模式在对象域的应用
- 外部源码索引（MustUnderstand）：[Amazon S3 consistency](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html) 的对象可见性、删除和版本语义
- 官方：[S3 Concepts](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)、[MinIO Java](https://min.io/docs/minio/linux/developers/java/API.html)
