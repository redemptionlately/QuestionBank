# MustRemember

- `CompletableFuture` 用 `thenApply/thenCompose/handle/exceptionally` 组合阶段；`allOf` 等待全部，`anyOf` 等待任一；异常沿 future 图传播
- `Semaphore` 限制并发许可（AqsEvidenceTest:286 许可数封顶并发），`BlockingQueue` 提供生产者消费者等待，`ReadWriteLock` 区分共享读和互斥写
- `Future.cancel(true)` 只是发出中断/取消信号；底层阻塞 I/O 是否停止取决于中断响应（对照 JvmConcurrencyEvidenceTest:29 volatile 立即可见 vs :49 普通字段可能被 JIT 提升）
- 有界线程池参数是 core/max/queue/rejection/keepAlive；队列无界会隐藏背压并推迟 OOM；真实双池在 [InfrastructureConfig.java](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) :22-53：import 2/4/100/CallerRuns/30s、general 2/8/200/CallerRuns/10s，由 InfrastructureExecutorRoutingTest 4 用例锁死
- `synchronized` 以监视器提供互斥和 happens-before；`ReentrantLock` 支持可中断、超时、公平策略和多个 `Condition`，但必须在 `finally` 解锁；分布式对应物是 [RedisLockService.java](../../app/src/main/java/com/allen/questionbank/redis/RedisLockService.java)（SET NX PX + Lua 比对 token 释放 + fencing INCR）
- `AtomicInteger/AtomicLong` 通过 CAS 更新单变量；CAS 可能自旋失败，不能替代多字段事务（提交路径靠 Redis 锁+FOR UPDATE+uk_submission_question 三层）；ABA 需要版本标记
- `CountDownLatch` 一次性等待计数归零，`CyclicBarrier` 可循环等待一组线程；`ThreadLocal` 隔离线程内状态但线程池中必须清理——真实例子 [RequestIdFilter.java](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) :33 put / :41 finally MDC.remove("requestId") 防 Tomcat 线程复用串号；另见 ExpiringCache:37-48 per-key synchronized singleflight
- 虚拟线程证据：JvmConcurrencyEvidenceTest:70（5000×50ms 阻塞任务优于 200 平台线程），Loadgen 用 newVirtualThreadPerTaskExecutor；虚拟线程不增加 DB 连接/锁/CPU/下游容量
- `CompletableFuture` 组合源码形态是：
  ```java
  CompletableFuture<Result> f = supplyAsync(this::load)
      .thenCompose(this::enrich)
      .orTimeout(500, MILLISECONDS)
      .exceptionally(this::fallback);
  ```
- 外部源码索引（MustRemember）：[CompletableFuture API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html) 的 `thenCompose/orTimeout/exceptionally`；[Executors](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html)

# MustUnderstand

- JDBC 阻塞线程和 CPU 计算线程混用会互相拖垮；线程池隔离是资源边界（import/general 双池即实例），拒绝策略是过载行为的一部分（两池均 CallerRuns，JvmConcurrencyEvidenceTest:86 演示 AbortPolicy 触发）
- 虚拟线程降低阻塞等待的线程成本，但不增加数据库连接、锁、CPU 和外部服务容量；pinning 和 ThreadLocal 仍需关注
- jvm 包 17 个证据用例（Aqs 6/ClassLoader 4/JMM 3/JvmConcurrency 4）默认构建全部运行，是手写最小实现与 JDK 行为的对照，不是只打印结论的演示
- 外部源码索引（MustUnderstand）：[Java 21 virtual threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) 的调度、阻塞和 pinning 说明
- 外部源码索引（MustUnderstand）：[ReentrantLock API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)、[AtomicInteger API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicInteger.html)、[CountDownLatch API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CountDownLatch.html)
- 外部源码索引（MustUnderstand）：[Java Concurrency package summary](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html) 的同步器、CAS 和内存可见性边界
- 官方：[CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)、[Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
