package com.allen.questionbank.jvm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AQS 不是背概念：这里手写一个可重入独占锁和一个共享模式信号量，
 * 用并发实验证明"锁到底解决了什么"，并且和 JDK 的 ReentrantLock 三方对照。
 *
 * 关键设计：每个结论都用"错误/无保护版本"做对照组，避免"加了锁所以对了"这种无信息量的断言。
 */
class AqsEvidenceTest {

    // ------------------------------------------------------------------
    // 手写实现：独占可重入锁（AQS state = 重入次数，exclusiveOwner = 持有线程）
    // ------------------------------------------------------------------
    static final class MiniReentrantLock implements Lock {

        private final Sync sync = new Sync();

        private static final class Sync extends java.util.concurrent.locks.AbstractQueuedSynchronizer {
            @Override
            protected boolean isHeldExclusively() {
                return getState() != 0 && getExclusiveOwnerThread() == Thread.currentThread();
            }

            @Override
            protected boolean tryAcquire(int acquires) {
                Thread current = Thread.currentThread();
                int c = getState();
                if (c == 0) {
                    if (compareAndSetState(0, acquires)) {
                        setExclusiveOwnerThread(current);
                        return true;
                    }
                } else if (getExclusiveOwnerThread() == current) {
                    int next = c + acquires;
                    if (next < 0) {
                        throw new Error("重入次数溢出");
                    }
                    setState(next);      // 重入：只需改 state，无需 CAS（已是持有者）
                    return true;
                }
                return false;
            }

            @Override
            protected boolean tryRelease(int releases) {
                if (getExclusiveOwnerThread() != Thread.currentThread()) {
                    throw new IllegalMonitorStateException("非持有者释放");
                }
                int c = getState() - releases;
                boolean free = (c == 0);
                if (free) {
                    setExclusiveOwnerThread(null);
                }
                setState(c);
                return free;
            }

            final ConditionObject newConditionObject() {
                return new ConditionObject();
            }
        }

        @Override public void lock() { sync.acquire(1); }
        @Override public void lockInterruptibly() throws InterruptedException { sync.acquireInterruptibly(1); }
        @Override public boolean tryLock() { return sync.tryAcquire(1); }
        @Override public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(time));
        }
        @Override public void unlock() { sync.release(1); }
        @Override public Condition newCondition() { return sync.newConditionObject(); }
    }

    // ------------------------------------------------------------------
    // 手写实现：共享模式信号量（AQS state = 可用许可数）
    // ------------------------------------------------------------------
    static final class MiniSemaphore {
        private final Sync sync;

        MiniSemaphore(int permits) { this.sync = new Sync(permits); }

        private static final class Sync extends java.util.concurrent.locks.AbstractQueuedSynchronizer {
            Sync(int permits) { setState(permits); }

            @Override
            protected int tryAcquireShared(int acquires) {
                for (;;) {
                    int available = getState();
                    int remaining = available - acquires;
                    // 负数=失败（入队等待）；非负=CAS 成功（可能还有剩余许可，继续唤醒后继）
                    if (remaining < 0 || compareAndSetState(available, remaining)) {
                        return remaining;
                    }
                }
            }

            @Override
            protected boolean tryReleaseShared(int releases) {
                for (;;) {
                    int current = getState();
                    int next = current + releases;
                    if (next < current) {
                        throw new Error("许可数溢出");
                    }
                    if (compareAndSetState(current, next)) {
                        return true;
                    }
                }
            }

            int permits() { return getState(); }
        }

        void acquire() throws InterruptedException { sync.acquireSharedInterruptibly(1); }
        void release() { sync.releaseShared(1); }
        int availablePermits() { return sync.permits(); }   // getState() 是 protected，只能由 Sync 自己暴露
    }

    // ------------------------------------------------------------------
    // 实验
    // ------------------------------------------------------------------

    /** 三方对照：无锁（必丢更新） / JDK ReentrantLock / 自研 AQS 锁。 */
    @Test
    void handWrittenAqsLockMatchesJdkAndFixesLostUpdates() throws Exception {
        int threads = 8;
        int perThread = 20_000;
        int expected = threads * perThread;

        // 无保护：刻意放大 read-modify-write 窗口（yield 让出 CPU）
        int[] unlocked = {0};
        runConcurrently(threads, perThread, () -> {
            int v = unlocked[0];
            Thread.yield();
            unlocked[0] = v + 1;
        });

        // JDK 锁
        ReentrantLock jdkLock = new ReentrantLock();
        int[] jdk = {0};
        runConcurrently(threads, perThread, () -> {
            jdkLock.lock();
            try {
                int v = jdk[0];
                Thread.yield();
                jdk[0] = v + 1;
            } finally {
                jdkLock.unlock();
            }
        });

        // 自研 AQS 锁（与 JDK 完全相同的调用方式）
        MiniReentrantLock mine = new MiniReentrantLock();
        int[] mineCount = {0};
        runConcurrently(threads, perThread, () -> {
            mine.lock();
            try {
                int v = mineCount[0];
                Thread.yield();
                mineCount[0] = v + 1;
            } finally {
                mine.unlock();
            }
        });

        System.out.println("[evidence] 自增 " + threads + "×" + perThread + " 期望=" + expected
                + " | 无锁=" + unlocked[0] + " | JDK ReentrantLock=" + jdk[0] + " | 自研AQS锁=" + mineCount[0]);

        assertTrue(unlocked[0] < expected, "无保护自增必须丢更新，否则这个对照组没有意义");
        assertEquals(expected, jdk[0], "JDK 锁下必须精确");
        assertEquals(expected, mineCount[0], "自研 AQS 锁必须与 JDK 行为一致");
    }

    /** 可重入：重入次数与释放次数必须严格配对——少释放一次，别的线程就永远进不来。 */
    @Test
    void reentrantCountMustPairWithReleaseOrOthersStarve() throws Exception {
        MiniReentrantLock lock = new MiniReentrantLock();
        lock.lock();
        lock.lock();
        lock.lock();

        AtomicBoolean otherGotIt = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                otherGotIt.set(true);
            } finally {
                lock.unlock();
            }
        });
        waiter.start();
        Thread.sleep(300);

        assertFalse(otherGotIt.get(), "持有者未释放前，其他线程必须拿不到锁");

        lock.unlock();
        lock.unlock();
        Thread.sleep(300);
        assertFalse(otherGotIt.get(), "获取 3 次只释放 2 次 = 锁仍被持有（重入计数必须配对）");

        lock.unlock();
        waiter.join(3000);
        assertTrue(otherGotIt.get(), "完全释放后等待线程必须获得锁");
        System.out.println("[evidence] 可重入：获取3次需释放3次，少释放1次等待线程仍被阻塞，补全释放后立即获得锁");
    }

    /** 非持有者释放：AQS 的 owner 校验必须抛 IllegalMonitorStateException。 */
    @Test
    void releaseByNonOwnerIsRejected() throws Exception {
        MiniReentrantLock lock = new MiniReentrantLock();
        lock.lock();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread intruder = new Thread(() -> {
            try {
                lock.unlock();
            } catch (Throwable e) {
                caught.set(e);
            }
        });
        intruder.start();
        intruder.join(3000);

        assertInstanceOf(IllegalMonitorStateException.class, caught.get(),
                "非持有线程释放必须被拒绝，否则任何线程都能解开别人的锁");
        lock.unlock();
        System.out.println("[evidence] 非持有者释放被拒：" + caught.get().getClass().getSimpleName());
    }

    /** Condition：await 会释放锁并挂起，signal 后必须重新拿到锁才继续。 */
    @Test
    void conditionAwaitReleasesLockAndResumesOnlyAfterSignal() throws Exception {
        MiniReentrantLock lock = new MiniReentrantLock();
        Condition ready = lock.newCondition();
        AtomicBoolean flag = new AtomicBoolean(false);
        AtomicInteger proceeded = new AtomicInteger();

        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                while (!flag.get()) {
                    ready.await();
                }
                proceeded.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        waiter.start();
        Thread.sleep(300);

        assertEquals(0, proceeded.get(), "条件未满足时必须挂在 await 上");
        // 关键证据：await 期间锁是可被别人获取的（否则就是死锁）
        assertTrue(lock.tryLock(), "await 必须释放锁，否则其他线程连 signal 都发不出");
        lock.unlock();

        lock.lock();
        try {
            flag.set(true);
            ready.signal();
        } finally {
            lock.unlock();
        }
        waiter.join(3000);
        assertEquals(1, proceeded.get(), "signal 后等待线程必须恢复并重新获得锁");
        System.out.println("[evidence] Condition：await 期间锁可被他人获取(证明已释放)，signal 后恢复执行并重新持锁");
    }

    /** 共享模式：许可数就是并发上限，且必须完整归还。 */
    @Test
    void sharedModeCapsConcurrencyAtPermitCount() throws Exception {
        int permits = 3;
        int threads = 12;
        MiniSemaphore sem = new MiniSemaphore(permits);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                try {
                    sem.acquire();
                    int now = current.incrementAndGet();
                    peak.updateAndGet(p -> Math.max(p, now));
                    Thread.sleep(30);
                    current.decrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    sem.release();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join(5000);
        }

        System.out.println("[evidence] 共享模式：许可=" + permits + " 线程=" + threads
                + " 实测并发峰值=" + peak.get() + " 结束时可用许可=" + sem.availablePermits());
        assertTrue(peak.get() >= 2, "必须真的并发起来，否则测不出上限");
        assertTrue(peak.get() <= permits, "并发峰值不能超过许可数");
        assertEquals(permits, sem.availablePermits(), "全部释放后许可必须完整归还");
    }

    /**
     * tryLock 的两面性（这个用例最初写错，是测试纠正了认知，值得记下来）：
     *  - 持有者自己调 tryLock：可重入，返回 true —— ReentrantLock 就是这个行为，
     *    很多人误以为"自己持锁时 tryLock 返回 false"，那是错的理解；
     *  - 别的线程持有时：立即返回 false，不排队、不阻塞（与 lock() 的区别在这里）。
     */
    @Test
    void tryLockIsReentrantForOwnerButFailsFastForOthers() throws Exception {
        MiniReentrantLock lock = new MiniReentrantLock();

        lock.lock();
        assertTrue(lock.tryLock(), "可重入锁：持有者自己调 tryLock 应重入成功（与 JDK ReentrantLock 一致）");
        lock.unlock();
        lock.unlock();

        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        assertTrue(acquired.await(5, TimeUnit.SECONDS), "持有线程必须先拿到锁");

        long start = System.nanoTime();
        boolean got = lock.tryLock();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(got, "锁被其他线程持有时 tryLock 必须返回 false");
        assertTrue(elapsedMs < 500, "tryLock 必须立即返回（实测 " + elapsedMs + "ms），不能像 lock() 那样排队");

        release.countDown();
        holder.join(5000);
        assertTrue(lock.tryLock(), "释放后 tryLock 必须成功");
        lock.unlock();
        System.out.println("[evidence] tryLock：持有者自己调用=重入成功(与 JDK 一致)；"
                + "其他线程持有时立即返回 false（" + elapsedMs + "ms，不排队）");
    }

    private static void runConcurrently(int threads, int perThread, Runnable body) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    body.run();
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join(60_000);
        }
    }
}
