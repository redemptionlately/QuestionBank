# 必须会背会写的验收
- 打开并按当天 study.md 的“源码索引（会背会写）”或“外部源码索引（会背会写）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。
- 请求 `/api/metrics`，验证 requests、failures、totalLatencyNanos，并比较成功请求、鉴权失败、429 和业务异常后的变化；说明重启进程后内存指标为何归零。
- 对照 `RequestMetrics.record` 与过滤器的 `request/failure/latency` 三组方法，说明为什么当前指标没有 P95、route 和 status 维度。

- 设计 submit timer/counter
- 写结构化日志
- 区分 Counter、Gauge、Timer、Histogram、trace/span，并列出低基数标签。
- 写出 Micrometer Timer 的 Java 代码和 submit 指标命名。
- 指出三个不应作为 tag 的高基数业务字段。

# 额外测试与追问

- 制造高延迟比较平均/P95
- 避免高基数 tag
