# 必须会背会写

- 对象由 `bucket + key` 定位；ETag 是存储服务返回的完整性线索，content hash 用于内容校验和去重，业务元数据仍在 MySQL
- 预签名 URL 携带对象、操作、权限和过期时间；它是短期授权，不是永久公开地址
- 上传、删除和数据库更新跨越两个系统，可能出现孤儿对象或悬空引用，需要 `PENDING/AVAILABLE/DELETE_PENDING` 等补偿状态
- 下载链路仍先检查用户/任务授权，再返回短期 URL；bucket 名、内部 key 和凭据不应暴露给不可信客户端
- 预签名下载的授权数据结构至少包含 `objectKey`、`method`、`expiresAt`、`userScope`；服务端签名而非客户端自选 key
- 当前 M0 没有 S3/MinIO 依赖或对象存储适配器；`ImportJob.sourceName` 只是任务输入标识，不是 bucket/key，也不代表文件已上传
- 外部源码索引（会背会写）：[AWS SDK Java S3 presigner](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html) 的 `GetObjectPresignRequest`

# 必须理解

- 对象存储与数据库有不同可见性和删除语义；孤儿清理需要保留期、幂等删除和审计日志
- 外部源码索引（必须理解）：[Amazon S3 consistency](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html) 的对象可见性、删除和版本语义
- 官方：[S3 Concepts](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html)、[MinIO Java](https://min.io/docs/minio/linux/developers/java/API.html)
