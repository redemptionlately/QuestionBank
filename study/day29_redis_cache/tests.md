# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 阅读 `ExpiringCache`、`CacheConfig` 和 `BankService` 的索引，说明当前单实例缓存的 key、TTL、miss 回源、evict 和并发 miss 行为
- 说明缓存进程重启或缓存故障时如何回源 MySQL，为什么不能把缓存当题库事实来源
- 写出 cache-aside 读写流程、SETNX/INCR 原子边界和穿透/击穿/雪崩的差异。
- 写出 `RedisTemplate` cache-aside 代码，并说明缓存失效顺序。
- 画出分布式锁 owner token、TTL 和释放校验。

# External

- 观察 TTL/INCR
- 设计击穿防护
