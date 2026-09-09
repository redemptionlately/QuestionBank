# Day60 Elasticsearch · 题目与标准解答（Solutions）

> ES 搜索投影；事实仍在 MySQL，ES 可重建。

## Current

### Q1. 为题库搜索设计 mapping，区分 text/keyword/数值/日期/不可索引。
```json
{
  "mappings": {
    "properties": {
      "paperId":      { "type": "long" },
      "title":        { "type": "text",
                        "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
      "content":      { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "status":       { "type": "keyword" },
      "bankId":       { "type": "long" },
      "publishedAt":  { "type": "date" },
      "versionNo":    { "type": "integer" },
      "internalNote": { "type": "object", "enabled": false }
    }
  }
}
```
- `text`：经 analyzer 分词做全文检索（title/content）；`keyword`：不分词，用于精确匹配、聚合、排序（status、title.keyword）；
- 数值 long/integer 用于范围/排序；date 用于时间范围/排序；
- `enabled:false`/`index:false`：只存不索引（如内部备注），节省开销但不能搜；
- mapping 字段类型确定后不能原地改类型，需 reindex。

---

### Q2. 写出全文检索、精确过滤、bool 查询与 search_after 分页。
```json
{
  "size": 20,
  "query": {
    "bool": {
      "must":   [ { "match": { "content": { "query": "操作系统 进程", "operator": "or" } } } ],
      "filter": [
        { "term":  { "status": "PUBLISHED" } },
        { "range": { "publishedAt": { "gte": "2026-01-01" } } }
      ],
      "should": [ { "term": { "title.keyword": "操作系统" } } ],
      "must_not": [ { "term": { "bankId": 0 } } ]
    }
  },
  "sort": [ { "publishedAt": "desc" }, { "paperId": "asc" } ],
  "search_after": [ 1756500000000, 1024 ]
}
```
- `must` 参与算分（相关性）、`filter` 不算分且可缓存（精确/范围过滤放这里更快）、`should` 加分可选、`must_not` 排除；
- `match` 对 text 分词检索，`term` 对 keyword/数值精确匹配（不要对 text 用 term）；
- 深分页用 `search_after`（必须有唯一稳定排序 tiebreaker，如 paperId），避免 `from+size` 大 offset 每分片都取 from+size 的开销；更大规模用 PIT。

---

### Q3. 设计 MySQL→Outbox→ES 投影的首次同步、重复、删除、重建。
- **首次同步**：全量批读 MySQL（按主键分页/游标），批量 bulk 写入 ES，记录同步水位；
- **增量**：业务事务写 MySQL + outbox 事件（同事务），投递器消费事件更新 ES；
- **重复事件**：文档用业务主键做 `_id`（如 paperId/versionId），写操作用 index 幂等覆盖，重复事件结果相同；
- **乱序更新**：文档带 `updatedAt`/版本号，更新时比较版本，旧事件不覆盖新数据；
- **删除**：发逻辑删除事件（或墓碑），ES 执行 delete；不能只删 MySQL 而不通知 ES；
- **重建**：mapping 变更或数据漂移时，全量灌到新索引，校验文档数/抽样一致后用别名原子切换（见 E2），旧索引保留以便回滚。

---

## External

### E1. refresh、replica、primary、near-real-time 关系。
写入先到 **primary shard**，成功后复制到 **replica shard**（高可用 + 读扩展）；文档写入内存 buffer 后，默认每 1s 一次 **refresh** 生成新 segment 才可被搜索，因此 ES 是**近实时（NRT）**——MySQL 已提交不等于 ES 立即可搜。`refresh=wait_for` 让写请求等到 refresh 后返回，提升可见性但增加写延迟、降低吞吐，不应在高频写路径默认开启。replica 不提升写入吞吐（写要同步副本），只提升可用性与读能力。

### E2. mapping 变更的 reindex、alias 切换与回滚。
类型不能原地修改，流程：① 建 `idx_v2`（新 mapping），应用同时写 v1/v2（双写）；② `_reindex` 从 v1 批量到 v2；③ 别名 `papers` 从 v1 原子 `_aliases`（remove v1 + add v2）切换，读流量零停机；④ 观察无误后下线 v1。回滚：别名切回 v1（v1 一直保留且双写期间仍更新），实现秒级回退。

### E3. 诊断查询慢、热点分片、深分页、堆压力的证据顺序。
- **查询慢**：`_search?profile=true` 看各子句耗时 → 检查是否对 text 用 term、是否缺 filter 缓存、是否全量聚合；
- **热点分片**：看各 shard 文档量/请求量（routing 是否倾斜），调整分片数/routing；
- **深分页**：确认是否用了大 from/size，改 search_after/PIT；
- **堆压力**：`_nodes/stats`、segment 数、fielddata/聚合内存、GC 日志；增大堆不是首选，先优化聚合基数与 segment merge。只加副本解决不了错误查询和热点分片。
