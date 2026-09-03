# 必须会背会写

- Elasticsearch 倒排索引把词项映射到文档，`text` 经过 analyzer 分词，`keyword` 用于精确匹配、聚合和排序；mapping 一旦确定，字段类型变更通常需要重建索引。
- `match` 适合全文检索，`term` 适合未分析值；bool 的 `must/filter/should/must_not` 分别表达相关性查询、可缓存过滤、可选条件和排除条件。
- 写入先进入 primary shard，再通过 replica 复制；refresh 影响搜索可见性，`refresh=wait_for` 会增加写延迟。
- 深分页使用 `search_after` 或 PIT，避免大 offset 的资源消耗；排序字段必须稳定且包含 tie-breaker。
- 索引别名和 reindex 支持 mapping 升级与无停机切换；业务事实仍保留在 MySQL，ES 是可重建搜索投影。
- 外部源码索引（会背会写）：[Elasticsearch mapping](https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping.html)、[Query DSL bool](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-bool-query.html)、[search_after](https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html)

# 必须理解

- ES 的近实时搜索、分片、副本和 refresh 与数据库事务提交不是同一个一致性边界；写 MySQL 成功不代表 ES 立即可搜。
- 通过 Outbox、重试和重建任务同步搜索投影；删除、重复事件、乱序更新和 mapping 失败都要可恢复。
- 分片数、routing、segment merge、堆和磁盘决定性能；只增加副本不能解决错误查询和热点分片。
- 外部源码索引（必须理解）：[Elasticsearch near real-time](https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html)、[index lifecycle](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html)
