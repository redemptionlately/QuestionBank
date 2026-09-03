# 必须会背会写

- `CompletableFuture` 用 `thenApply/thenCompose/handle/exceptionally` 组合阶段；`allOf` 等待全部，`anyOf` 等待任一；异常沿 future 图传播
- `Semaphore` 限制并发许可，`BlockingQueue` 提供生产者消费者等待，`ReadWriteLock` 区分共享读和互斥写
- `Future.cancel(true)` 只是发出中断/取消信号；底层阻塞 I/O 是否停止取决于中断响应和超时配置
- 有界线程池参数是 core/max/queue/rejection/keepAlive；队列无界会隐藏背压并推迟 OOM
- `synchronized` 以监视器提供互斥和 happens-before；`ReentrantLock` 支持可中断、超时、公平策略和多个 `Condition`，但必须在 `finally` 解锁
- `AtomicInteger/AtomicLong` 通过 CAS 更新单变量；CAS 可能自旋失败，不能替代多字段事务；ABA 需要版本标记或引用包装处理
- `CountDownLatch` 一次性等待计数归零，`CyclicBarrier` 可循环等待一组线程，`ThreadLocal` 隔离线程内状态但在线程池中必须清理防止数据串线
- `CompletableFuture` 组合源码形态是：
  ```java
  CompletableFuture<Result> f = supplyAsync(this::load)
      .thenCompose(this::enrich)
      .orTimeout(500, MILLISECONDS)
      .exceptionally(this::fallback);
  ```
- 外部源码索引（会背会写）：[CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html) 的 `thenCompose/orTimeout/exceptionally`；[Executors](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html)

# 必须理解

- JDBC 阻塞线程和 CPU 计算线程混用会互相拖垮；线程池隔离是资源边界，拒绝策略是过载行为的一部分
- 虚拟线程降低阻塞等待的线程成本，但不增加数据库连接、锁、CPU 和外部服务容量；pinning 和 ThreadLocal 仍需关注
- 外部源码索引（必须理解）：[Java 21 virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) 的调度、阻塞和 pinning 说明
- 外部源码索引（会背会写）：[ReentrantLock API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)、[AtomicInteger API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html)、[CountDownLatch API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CountDownLatch.html)
- 外部源码索引（必须理解）：[Java Concurrency package summary](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html) 的同步器、CAS 和内存可见性边界
- 官方：[CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)、[Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
