# 必须会背会写的验收
- 打开并按当天 study.md 的“源码索引（会背会写）”或“外部源码索引（会背会写）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 写 allOf/anyOf 异常测试
- 有界生产者消费者
- 写出 Semaphore、ReadWriteLock、Future.cancel、虚拟线程和线程池隔离的适用边界。
- 写出 CompletableFuture 的 thenCompose、timeout 和 exceptionally 组合。
- 设计有界 BlockingQueue 的拒绝/背压策略。
- 分别写出 `ReentrantLock`、`AtomicInteger`、`CountDownLatch`、`CyclicBarrier` 和线程池中的 `ThreadLocal` 清理示例。
- 写出 `ReentrantLock` 的 `try/finally unlock` 和可中断获取示例。
- 写出 `ThreadLocal` 在线程池任务结束后的 `remove` 清理示例。

# 额外测试与追问

- 比较虚拟/平台线程
- 测队列等待
- 用 `AtomicInteger` 写无锁计数并构造 CAS 失败场景，说明多字段更新为何不能直接替代事务。
- 写出 `CountDownLatch` 与 `CyclicBarrier` 的最小示例，并在线程池任务结束后清理 `ThreadLocal`。
