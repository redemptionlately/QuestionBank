# Day42 JUC Advanced · 题目与标准解答（Solutions）

> Java 21 `java.util.concurrent`，全部为可运行最小示例（C++偏好者注意：以下为 Java），并逐项对齐项目真实并发代码：双异步线程池、ExpiringCache per-key singleflight、RequestIdFilter 的 MDC(ThreadLocal) 清理、Redis 分布式锁，以及 jvm 包 4 个证据测试类。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
结合本项目真实代码：
- [importjob/ImportJobWorker.java:12](../../app/src/main/java/com/allen/questionbank/importjob/ImportJobWorker.java) 用 `@Async("importTaskExecutor")` 在专用池异步执行；默认 `@Async` 走 generalTaskExecutor（InfrastructureConfig:55-56）；
- [common/ExpiringCache.java:37](../../app/src/main/java/com/allen/questionbank/common/ExpiringCache.java) 的 getOrLoad 用 **per-key 对象级 synchronized singleflight**（:48，并发 miss 只 load 一次，有 6 用例测试 ExpiringCacheSingleflightTest）；
- [common/RequestMetrics.java:7-9](../../app/src/main/java/com/allen/questionbank/common/RequestMetrics.java) 三个 AtomicLong；[common/RateLimitFilter.java](../../app/src/main/java/com/allen/questionbank/common/RateLimitFilter.java) 固定窗口 + SWEEP_EVERY=256（:29，每 256 请求顺手清扫过期窗口）；
- [common/RequestIdFilter.java:41](../../app/src/main/java/com/allen/questionbank/common/RequestIdFilter.java) 在 finally 里 `MDC.remove("requestId")`——MDC 底层就是 ThreadLocal，这是**线程池（Tomcat 工作线程复用）下必须清理 ThreadLocal 的真实例子**，不清理会把上个请求的 requestId 串给下个请求。

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
**拒绝/背压策略**：有界队列满时，ThreadPoolExecutor 按 RejectedExecutionHandler 处理——AbortPolicy（抛异常，默认）、CallerRunsPolicy（让提交线程自己跑，反压）、Discard/DiscardOldest（丢弃，慎用）。**无界队列会把背压隐藏成无限堆积，最终 OOM**。项目证据：JvmConcurrencyEvidenceTest.boundedQueuePlusSaturatedPoolTriggersRejectionPolicy（:86，core=1+队列 1，第 3 个任务触发 AbortPolicy）；项目两个异步池都显式选 CallerRunsPolicy（不丢任务、让调用方减速）。

---

### Q4. 写出 Semaphore/ReadWriteLock/Future.cancel/虚拟线程/线程池隔离的适用边界。
- **Semaphore**：限制同时访问某资源的许可数，`acquire/release`（release 放 finally）；项目证据 AqsEvidenceTest.sharedModeCapsConcurrencyAtPermitCount（:286，并发峰值≤许可数 3、结束后许可完整归还，对照手写 MiniSemaphore :92-132）；
- **ReadWriteLock**：读读共享、读写/写写互斥，适合读多写少；写锁可设公平防饥饿；
- **Future.cancel(true)**：只是发中断信号（interrupt），能否真正停下取决于任务是否响应中断/是否可中断的阻塞；对不可中断的阻塞 I/O 或无视中断的死循环无效（JvmConcurrencyEvidenceTest:49 演示普通 boolean 标志可能被 JIT 提升导致 worker 停不下来，:29 对照 volatile 立即可见）；
- **虚拟线程（Java 21）**：大幅降低阻塞等待时的线程成本，适合 I/O 密集；但**不增加**数据库连接、锁、CPU、下游容量；synchronized 块内阻塞可能 pin 载体线程，ThreadLocal 大量使用仍有内存开销。项目实证：JvmConcurrencyEvidenceTest.virtualThreadsHandleBlockingWorkWithFarLessOverhead（:70，5000 个 50ms 阻塞任务快于 200 平台线程池）；Loadgen 用 newVirtualThreadPerTaskExecutor 承载开环响应；
- **线程池隔离（bulkhead）**：JDBC 阻塞线程与 CPU 计算线程用不同池。项目 import 池（core2/max4/queue100）与 general 池（core2/max8/queue200）物理分离，导入慢任务不拖垮通用异步，参数由 InfrastructureExecutorRoutingTest 反射断言锁死。

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

// 线程池中的 ThreadLocal 必须 remove——项目真实形态是 RequestIdFilter 的 MDC 清理：
// MDC.put("requestId", id); try { chain.doFilter(...); } finally { MDC.remove("requestId"); }
static final ThreadLocal<String> USER = new ThreadLocal<>();
public void handle(String user) {
    USER.set(user);
    try { /* 处理 */ } finally { USER.remove(); }   // 关键：任务结束清理
}
```
**CAS 局限**：CAS 只保证单个变量的原子读改写，自旋在高竞争下浪费 CPU；多字段一致性更新不能靠多个 CAS 拼凑，需要锁或数据库事务。**ABA**：值 A→B→A，普通 CAS 无法察觉，用 `AtomicStampedReference`（版本戳）或 `AtomicMarkableReference` 解决。项目分布式侧的对应物是 RedisLockService：SET NX PX 抢锁 + Lua 比对 token 才 DEL（防误删他人锁）+ 独立 fencing key INCR 单调递增令牌（RedisLockAndRateLimitIntegrationTest 6 用例门控验证）。

---

### Q7. 有界线程池参数。
`corePoolSize / maximumPoolSize / workQueue(有界) / threadFactory / RejectedExecutionHandler / keepAliveTime`。任务先占核心线程 → 满则入队 → 队列满才扩到 max → 还满则触发拒绝策略。本项目 [common/InfrastructureConfig.java:22-53](../../app/src/main/java/com/allen/questionbank/common/InfrastructureConfig.java) 真实参数：
- importTaskExecutor：core 2 / max 4 / queue 100 / 前缀 import-worker- / CallerRunsPolicy / 关停等待 30s；
- generalTaskExecutor：core 2 / max 8 / queue 200 / 前缀 general-async- / CallerRunsPolicy / 关停等待 10s；
命名线程工厂让 thread dump/JFR 可直接辨认任务来源；@EnableAsync 在启动类 :8、@EnableScheduling 在 InfrastructureConfig:13。

---

## External

### E1. 比较虚拟/平台线程并测队列等待。
平台线程直接映射 OS 线程、创建成本高、数量受限；虚拟线程由 JDK 在少量载体线程上调度，阻塞时卸载，适合大量并发 I/O。项目证据：JvmConcurrencyEvidenceTest:70 固定 5000×50ms 阻塞任务下虚拟线程快于 200 平台线程池；Loadgen 开环压测用虚拟线程承载响应、producer 仍为单平台线程保到达节拍。用队列大小/等待时间观察背压（InfrastructureExecutorRoutingTest.poolParametersMatchDeclaredIntent :53 反射锁死两池参数）。结论：虚拟线程不改变下游容量上限（DB 连接池仍是硬上限）。

### E2. AtomicInteger 无锁计数 + CAS 失败场景 + 多字段为何不能替代事务。
高并发下 `incrementAndGet` 内部 CAS 可能多次重试才成功（高竞争用 LongAdder 吞吐更高；AqsEvidenceTest.handWrittenAqsLockMatchesJdkAndFixesLostUpdates :139 的 8 线程×20000 三方对照证明：无锁必丢更新、锁保护才精确等于 160000）。要同时原子更新"余额+流水号+状态"等多个相关字段时，分别 CAS 无法保证同时一致，必须用锁/数据库事务——本项目提交路径因此是三层组合：Redis 锁（RedisGuardedPracticeSubmitter，减冲突）→ DB SELECT…FOR UPDATE 行锁（PracticeService.submit）→ uk_submission_question 唯一约束最终兜底，而不是用原子变量拼凑一致性。

### E3. CountDownLatch vs CyclicBarrier 最小示例。
Latch 是"一个/多个线程等其他 N 个完成"，计数到 0 后不可重置；Barrier 是"N 个线程互相等待到齐再一起走"，可 `reset()` 循环使用，适合分阶段并行计算。AqsEvidenceTest 中 conditionAwaitReleasesLockAndResumesOnlyAfterSignal（:245）还演示了 Condition.await 期间必须释放锁（否则其他线程无法获取形成死锁）、signal 后重新拿锁才继续。
