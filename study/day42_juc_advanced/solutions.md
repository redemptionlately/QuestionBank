# Day42 JUC Advanced · 题目与标准解答（Solutions）

> Java 21 `java.util.concurrent`，全部为可运行最小示例（C++偏好者注意：以下为 Java）。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
结合本项目：`ImportJobWorker` 用 `@Async("importTaskExecutor")` 线程池异步执行；`ExpiringCache/RateLimitFilter/RequestMetrics` 用 `ConcurrentHashMap/AtomicLong/AtomicInteger` 保证并发正确。

---

### Q2. 写 allOf/anyOf 异常测试。
```java
CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> 1);
CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> { throw new IllegalStateException("boom"); });

CompletableFuture<Void> all = CompletableFuture.allOf(a, b);   // 全部完成；任一异常则 all 异常
assertThatThrownBy(all::join).hasCauseInstanceOf(IllegalStateException.class);

CompletableFuture<Object> any = CompletableFuture.anyOf(
        CompletableFuture.supplyAsync(() -> "first"),
        CompletableFuture.supplyAsync(() -> 2));               // 任一完成即完成
assertThat(any.join()).isEqualTo("first");
```
异常沿 future 组合图传播：`allOf` 等所有、任一失败则整体异常；`anyOf` 取最先完成（含最先异常）。

---

### Q3. 有界生产者消费者。
```java
BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);   // 有界队列=背压
// 生产者：put 在队列满时阻塞（或 offer 带超时/拒绝）
// 消费者：take 在空时阻塞
class Worker implements Runnable {
    public void run() {
        try { while (!Thread.currentThread().isInterrupted()) process(queue.take()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }   // 恢复中断标志并退出
    }
}
```
**拒绝/背压策略**：有界队列满时，ThreadPoolExecutor 按 RejectedExecutionHandler 处理——AbortPolicy（抛异常，默认）、CallerRunsPolicy（让提交线程自己跑，反压）、Discard/DiscardOldest（丢弃，慎用）。**无界队列会把背压隐藏成无限堆积，最终 OOM**。

---

### Q4. 写出 Semaphore/ReadWriteLock/Future.cancel/虚拟线程/线程池隔离的适用边界。
- **Semaphore**：限制同时访问某资源的许可数（如限制并发 PDF 解析数），`acquire/release`（release 放 finally）；
- **ReadWriteLock**：读读共享、读写/写写互斥，适合读多写少；写锁可设公平防饥饿；
- **Future.cancel(true)**：只是发中断信号（interrupt），能否真正停下取决于任务是否响应中断/是否可中断的阻塞；对不可中断的阻塞 I/O 或无视中断的死循环无效；
- **虚拟线程（Java 21）**：大幅降低阻塞等待时的线程成本（百万级），适合 I/O 密集；但**不增加**数据库连接、锁、CPU、下游容量；synchronized 块内阻塞可能 pin 载体线程，ThreadLocal 大量使用仍有内存开销；
- **线程池隔离（bulkhead）**：JDBC 阻塞线程与 CPU 计算线程用不同池，避免一类慢操作耗尽全部线程拖垮全局。

---

### Q5. 写出 CompletableFuture 的 thenCompose/timeout/exceptionally 组合。
```java
CompletableFuture<Result> f = CompletableFuture
        .supplyAsync(this::load)                 // CompletableFuture<Raw>
        .thenCompose(this::enrich)               // 扁平化串联另一个返回 CF 的阶段（类似 flatMap）
        .orTimeout(500, TimeUnit.MILLISECONDS)   // 超时整体异常完成（TimeoutException）
        .exceptionally(ex -> fallback(ex));      // 兜底，把异常转为正常值
// thenApply：同步变换值（map）；handle：无论成败都能处理
```

---

### Q6. 分别写 ReentrantLock/AtomicInteger/CountDownLatch/CyclicBarrier/ThreadLocal 清理示例。
```java
// ReentrantLock：可中断/超时获取，必须 finally 解锁
private final ReentrantLock lock = new ReentrantLock();
public void mutate() throws InterruptedException {
    if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) throw new IllegalStateException("获取锁超时");
    try { /* 临界区 */ } finally { lock.unlock(); }
}

// AtomicInteger：CAS 无锁单变量
AtomicInteger seq = new AtomicInteger();
int next = seq.incrementAndGet();

// CountDownLatch：一次性，等 N 个条件归零
CountDownLatch done = new CountDownLatch(2);
exec.submit(() -> { work(); done.countDown(); });
exec.submit(() -> { work(); done.countDown(); });
done.await(1, TimeUnit.SECONDS);

// CyclicBarrier：可循环，让 N 个线程在屏障处汇合后一起继续
CyclicBarrier barrier = new CyclicBarrier(3, () -> System.out.println("all arrived"));

// 线程池中的 ThreadLocal 必须 remove，防止线程复用导致用户数据串线
static final ThreadLocal<String> USER = new ThreadLocal<>();
public void handle(String user) {
    USER.set(user);
    try { /* 处理 */ } finally { USER.remove(); }   // 关键：任务结束清理
}
```
**CAS 局限**：CAS 只保证单个变量的原子读改写，自旋在高竞争下浪费 CPU；多字段一致性更新不能靠多个 CAS 拼凑，需要锁或数据库事务。**ABA**：值 A→B→A，普通 CAS 无法察觉，用 `AtomicStampedReference`（版本戳）或 `AtomicMarkableReference` 解决。

---

### Q7. 有界线程池参数。
`corePoolSize / maximumPoolSize / workQueue(有界) / threadFactory / RejectedExecutionHandler / keepAliveTime`。任务先占核心线程 → 满则入队 → 队列满才扩到 max → 还满则触发拒绝策略。本项目 `InfrastructureConfig` 配 2/4 线程、100 队列、命名线程工厂（便于 thread dump 辨认）。

---

## External

### E1. 比较虚拟/平台线程并测队列等待。
平台线程直接映射 OS 线程、创建成本高、数量受限；虚拟线程由 JDK 在少量载体线程上调度，阻塞时卸载，适合大量并发 I/O。压测对比：固定任务数下两者吞吐可能接近（瓶颈在 DB），但虚拟线程内存占用更低；用队列大小/等待时间指标观察背压。结论：虚拟线程不改变下游容量上限。

### E2. AtomicInteger 无锁计数 + CAS 失败场景 + 多字段为何不能替代事务。
高并发下 `incrementAndGet` 内部 CAS 可能多次重试才成功（用累加器 LongAdder 在高竞争下吞吐更高）。要同时原子更新“余额 + 流水号 + 状态”三个相关字段时，分别 CAS 无法保证三者同时一致（中间崩溃/交错会产生不一致），必须用锁/数据库事务，这正是本项目提交用 `@Transactional` + 行锁而非多个原子变量的原因。

### E3. CountDownLatch vs CyclicBarrier 最小示例。
Latch 是“一个/多个线程等其他 N 个完成”，计数到 0 后不可重置；Barrier 是“N 个线程互相等待到齐再一起走”，可 `reset()` 循环使用，适合分阶段并行计算。
