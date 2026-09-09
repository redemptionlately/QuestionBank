# MustRemember

- `multipart/form-data` 以 boundary 分段传输文件和字段；服务端限制字节数、MIME、扩展名、页数、解析时间和并发数
- 原始文件名属于不可信输入；随机 storage key、路径规范化、拒绝 `..` 和专用目录防止路径穿越
- 文件元数据至少包含 owner、size、contentType、contentHash、storageKey、状态、创建时间和解析错误
- 上传状态可分为 `RECEIVED/STORED/PARSING/IMPORTED/FAILED`，状态变化必须带 owner 和任务关联
- 当前项目的 `POST /api/import-jobs` 只接收 JSON `sourceName` 并创建持久任务；`MultipartFile`、真实 PDF 字节和对象存储尚未接入，不能把任务入口写成已完成文件上传
- Spring MVC 文件参数的源码形态是：
  ```java
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ImportView upload(@RequestPart("file") MultipartFile file,
                    @AuthenticationPrincipal AuthPrincipal user) {
      return importService.receive(user.userId(), file);
  }
  ```
- 外部源码索引（MustRemember）：[Spring MultipartFile](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html) 的 `getInputStream/getSize/getContentType`；[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html) 的 allow-list

# MustUnderstand

- 文件是恶意输入，扩展名、声明 MIME 和 magic bytes 可能不一致；PDF 解析可能造成 CPU、内存和递归资源消耗
- 对象成功而数据库失败产生孤儿对象，数据库成功而对象失败产生悬空引用；两者需要补偿状态和清理任务
- 外部源码索引（MustUnderstand）：[OWASP Unrestricted Upload](https://owasp.org/www-community/vulnerabilities/Unrestricted_File_Upload) 的路径穿越、解析器和资源消耗边界
- 官方：[OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[Spring Multipart](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)
