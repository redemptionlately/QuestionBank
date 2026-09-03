# 必须会背会写的验收

- 为题库搜索设计 mapping，区分 `text`、`keyword`、数值、日期和不可索引字段。
- 写出全文检索、精确过滤、bool 查询和 `search_after` 分页示例。
- 设计 MySQL→Outbox→ES 投影的首次同步、重复事件、删除和重建流程。

# 额外测试与追问

- 解释 refresh、replica、primary 和 near-real-time 的关系。
- 设计 mapping 变更的 reindex、alias 切换和回滚。
- 诊断查询慢、热点分片、深分页和堆压力的证据顺序。
