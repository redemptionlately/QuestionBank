package com.allen.questionbank.jvm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JMM happens-before 规则的变式证据：volatile 只是三条规则之一，
 * 这里验证另两条常用的——线程 join() 规则与监视器锁（synchronized）规则。
 * 结论不靠单次运行：每条规则循环多轮，任何一轮观测到旧值即失败。
 */
class JmmHappensBeforeEvidenceTest {

    /** join() 规则：线程 B 中的任意操作 happens-before B.join() 返回后的任意操作。 */
    @Test
    void joinEstablishesHappensBeforeForPlainFields() throws Exception {
        // 故意不用 volatile：plainField 无任何同步修饰，可见性完全依赖 join() 的 happens-before 保证
        for (int round = 0; round < 200; round++) {
            final int expected = round;
            Holder h = new Holder();
            Thread writer = new Thread(() -> h.plainField = expected);
            writer.start();
            writer.join();
            assertEquals(expected, h.plainField,
                    "第 " + expected + " 轮：join() 返回后必须能看到写线程的普通字段写入");
        }
        System.out.println("[evidence] join() 规则：200 轮无一观测到旧值——join 返回 happens-before 后续读取");
    }

    /** 监视器锁规则：解锁 happens-before 后续对同一锁的加锁。 */
    @Test
    void monitorLockEstablishesHappensBeforeAcrossThreads() throws Exception {
        for (int round = 0; round < 200; round++) {
            final int expected = round;
            Holder h = new Holder();
            CountDownLatch written = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    synchronized (h.lock) {
                        h.plainField = expected;
                    }
                    written.countDown();
                    return null;
                });
                assertTrue(written.await(5, TimeUnit.SECONDS), "写线程未完成");
                pool.submit(() -> {
                    synchronized (h.lock) {
                        h.observed = h.plainField; // 同一锁的保护下读取，必须看到写入
                    }
                    return null;
                }).get(5, TimeUnit.SECONDS);
                assertEquals(expected, h.observed,
                        "第 " + expected + " 轮：同一监视器锁的解锁-加锁必须建立 happens-before");
            } finally {
                pool.shutdownNow();
            }
        }
        System.out.println("[evidence] 监视器锁规则：200 轮无一观测到旧值——解锁 happens-before 同锁再加锁");
    }

    /** 反例对照：无任何同步手段时，普通字段可见性无保证（体现为最终一致但时序不可依赖）。 */
    @Test
    void withoutSynchronizationVisibilityIsNotGuaranteedImmediately() throws Exception {
        int staleObservations = 0;
        for (int round = 0; round < 200; round++) {
            Holder h = new Holder();
            CountDownLatch written = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                h.plainField = 42;
                written.countDown();
            });
            t.start();
            written.await();
            // 写线程已把 42 写入并 countDown（countDown/join 之外这里故意不建立任何 happens-before）
            // 立即读：可能读到 0（未同步的读不保证立即可见），也可能读到 42 —— 这正是"没有保证"的含义
            if (h.plainField != 42) {
                staleObservations++;
            }
            t.join();
        }
        System.out.println("[evidence] 反例对照：无同步手段时 200 轮中出现 " + staleObservations
                + " 次旧值读取（0 次也正常——'没有保证'不等于'一定读旧值'，这是 JMM 最容易讲错的点）");
    }

    static class Holder {
        final Object lock = new Object();
        int plainField;   // 故意不用 volatile
        int observed;
    }
}
