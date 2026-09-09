package com.allen.questionbank.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行器路由：@Async 默认池必须是 generalTaskExecutor，与 importTaskExecutor 按职责分离。 */
class InfrastructureExecutorRoutingTest {

    private final InfrastructureConfig config = new InfrastructureConfig();
    private final ThreadPoolTaskExecutor general = config.generalTaskExecutor();
    private final ThreadPoolTaskExecutor importPool = config.importTaskExecutor();

    @AfterEach
    void shutdown() {
        general.shutdown();
        importPool.shutdown();
    }

    @Test
    void defaultAsyncExecutorIsGeneralPool() {
        Executor defaultExecutor = config.getAsyncExecutor();
        assertTrue(defaultExecutor instanceof ThreadPoolTaskExecutor);
        assertEquals("general-async-", ((ThreadPoolTaskExecutor) defaultExecutor).getThreadNamePrefix(),
                "@Async 默认池不得再绑定到 import 专用池");
    }

    @Test
    void twoPoolsAreDistinctInstancesWithDistinctPrefixes() {
        assertNotSame(general, importPool);
        assertEquals("general-async-", general.getThreadNamePrefix());
        assertEquals("import-worker-", importPool.getThreadNamePrefix());
    }

    @Test
    void tasksRunOnThreadsWithPoolPrefix() throws Exception {
        AtomicReference<String> generalThread = new AtomicReference<>();
        AtomicReference<String> importThread = new AtomicReference<>();
        general.submit(() -> generalThread.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        importPool.submit(() -> importThread.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
        assertTrue(generalThread.get().startsWith("general-async-"), "实际线程名: " + generalThread.get());
        assertTrue(importThread.get().startsWith("import-worker-"), "实际线程名: " + importThread.get());
    }
}
