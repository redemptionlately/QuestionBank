# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。
- 为 M0 的题库列表设计分页、稳定排序、最大 page size、状态码和版本兼容策略。

- 写出带 `@Valid @RequestBody` 的 POST 接口和一个嵌套 DTO。
- 写出 `@PathVariable`、`@RequestHeader`、`@NotBlank`、`@NotEmpty` 的使用。
- 验证缺少题库名称、空题目列表、空题干分别返回 400 和 `VALIDATION_ERROR`。
- 验证未知路径返回 404，内部异常不返回 SQL 或堆栈。
- 写出统一错误响应 DTO，包含稳定 `code`、可读 `message`、`requestId` 和时间字段。

# External

- 发送超长标题，比较接口校验、数据库长度和响应业务码。
- 发送未知 JSON 字段，记录 Jackson 的实际行为。
- 发送错误 Content-Type，说明请求在哪一层失败。
- 设计一次 PUT、PATCH 和 DELETE 请求的幂等性、状态码和兼容字段。
- 写出一个游标分页响应 DTO，包含数据、下一页游标和是否还有更多，并限制单页大小。
