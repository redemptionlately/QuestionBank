package com.allen.questionbank.jvm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 核心与并发的可复现实验：每一条结论都必须能在本机跑出原始输出，
 * 而不是"我背过这个概念"。输出带 [evidence] 前缀，便于 tee 到 output/。
 */
class JvmConcurrencyEvidenceTest {

    static class Flags {
        volatile boolean volatileStop = false;
        boolean plainStop = false;
    }

    @Test
    void volatileWriteIsImmediatelyVisibleToReader() throws Exception {
        Flags flags = new Flags();
        AtomicLong loops = new AtomicLong();
        Thread worker = new Thread(() -> {
            while (!flags.volatileStop) {
                loops.incrementAndGet();
            }
        });
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(500);
        flags.volatileStop = true;
        worker.join(3000);

        assertFalse(worker.isAlive(),
                "volatile 写对读线程立即可见，工作线程必须在 3 秒内退出循环");
        System.out.println("[evidence] volatile 版本：已停止=true，循环次数=" + loops.get());
    }

    @Test
    void plainFieldReadCanBeHoistedSoWorkerMayNeverStop() throws Exception {
        Flags flags = new Flags();
        AtomicLong loops = new AtomicLong();
        Thread worker = new Thread(() -> {
            while (!flags.plainStop) {
                loops.incrementAndGet();
            }
        });
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(500);
        flags.plainStop = true;
        worker.join(3000);

        boolean stopped = !worker.isAlive();
        System.out.println("[evidence] 无 volatile 版本：3 秒内停止=" + stopped + "，循环次数=" + loops.get());
        System.out.println("[evidence] 结论：普通字段不保证可见性，JIT 可能把读提升到循环外；"
                + "跨线程停止标志必须用 volatile / 锁 / 原子类，不能用普通 boolean");
    }

    @Test
    void virtualThreadsHandleBlockingWorkWithFarLessOverhead() throws Exception {
        int tasks = 5000;
        long sleepMillis = 50;

        long platformMs = measure(tasks, sleepMillis, Executors.newFixedThreadPool(200));
        long virtualMs = measure(tasks, sleepMillis, Executors.newVirtualThreadPerTaskExecutor());

        System.out.println("[evidence] 平台线程池(200 线程) 完成 " + tasks + " 个阻塞任务: " + platformMs + " ms");
        System.out.println("[evidence] 虚拟线程    完成 " + tasks + " 个阻塞任务: " + virtualMs + " ms");

        assertTrue(virtualMs < platformMs,
                "同等阻塞负载下虚拟线程耗时应显著低于固定 200 线程的平台线程池：platform="
                        + platformMs + "ms, virtual=" + virtualMs + "ms");
    }

    @Test
    void boundedQueuePlusSaturatedPoolTriggersRejectionPolicy() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            pool.submit(() -> block(200));
            pool.submit(() -> block(200));
            assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> block(200)),
                    "线程数 1 + 队列 1 时，第三个任务必须触发拒绝策略");
            System.out.println("[evidence] 有界队列 + AbortPolicy：第 3 个任务被拒绝，符合预期");
        } finally {
            pool.shutdownNow();
        }
    }

    private long measure(int tasks, long sleepMillis, ExecutorService pool) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.submit(() -> block(sleepMillis));
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(2, TimeUnit.MINUTES);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(finished, "任务必须在 2 分钟内全部执行完，否则计时无意义");
        return elapsedMillis;
    }

    private void block(long millis) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
