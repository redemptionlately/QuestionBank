# MustRemember

- [ImportJobController.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobController.java) :13-33：`POST /api/import-jobs`（类级 STUDENT，:20-21）只接收 JSON CreateJobRequest(sourceName)，`GET /{id}` 轮询；[ImportJobService.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobService.java) :17 提交落库、:27 afterCommit 才触发 worker；[ImportJobWorker.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java) :12-24 `@Async("importTaskExecutor")` 异步处理
- 真实状态机：[ImportJob.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJob.java) :37-41 的 start/succeed/fail/retry + [ImportJobStatus.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobStatus.java) 枚举 RECEIVED/PROCESSING/SUCCEEDED/FAILED；FAILED→RECEIVED 重试由 [ImportJobRetrySweeper.java](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobRetrySweeper.java) 驱动（最多 3 次、30<<n 秒封顶 600）；表结构在 V3__async_import_jobs.sql
- `multipart/form-data` 以 boundary 分段传输文件和字段；服务端限制字节数、MIME、扩展名、页数、解析时间和并发数（app 当前未配置 spring.servlet.multipart，文件上传未接入）
- 原始文件名属于不可信输入；随机 storage key、路径规范化、拒绝 `..` 和专用目录防止路径穿越（同类 containment 实践见 frontend/server.js:58-60）
- 文件元数据至少包含 owner、size、contentType、contentHash、storageKey、状态、创建时间和解析错误；目标状态机 `RECEIVED/STORED/PARSING/IMPORTED/FAILED` 是在现有 RECEIVED/PROCESSING/SUCCEEDED/FAILED 之上的扩展设计，不得写成已实现
- Spring MVC 文件参数的源码形态是：
  ```java
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  JobView upload(@RequestPart("file") MultipartFile file,
                 CurrentUser user) {
      return importJobService.receive(user.require().userId(), file);
  }
  ```
- 外部源码索引（MustRemember）：[Spring MultipartFile](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html) 的 `getInputStream/getSize/getContentType`；[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html) 的 allow-list

# MustUnderstand

- 文件是恶意输入，扩展名、声明 MIME 和 magic bytes 可能不一致（只信 magic bytes）；PDF 解析可能造成 CPU、内存和递归资源消耗
- 对象成功而数据库失败产生孤儿对象，数据库成功而对象失败产生悬空引用；两者需要补偿状态和清理任务（现有 ImportJobRetrySweeper 是"数据库侧任务状态补偿"的已实现雏形，对象存储侧补偿仍待设计）
- pom 无 S3/MinIO 依赖、application.yml 无 multipart 配置：文件上传与对象存储均为设计态，不能把任务入口（JSON sourceName）说成已完成字节上传
- 外部源码索引（MustUnderstand）：[OWASP Unrestricted Upload](https://owasp.org/www-community/vulnerabilities/Unrestricted_File_Upload) 的路径穿越、解析器和资源消耗边界
- 官方：[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[Spring Multipart](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)
