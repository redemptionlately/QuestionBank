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

    @Test
    void poolParametersMatchDeclaredIntent() {
        // general 池：core2/max8/queue200，CallerRuns 兜底，关停等待 10s
        assertEquals(2, general.getCorePoolSize(), "general 池 core 必须是 2");
        assertEquals(8, general.getMaxPoolSize(), "general 池 max 必须是 8");
        assertEquals(200, general.getQueueCapacity(), "general 池队列必须 200");
        // 关停语义与拒绝策略没有 public getter，用反射读字段——PIT 删除 set 调用的变异
        // 会让字段回落默认值（null/false/0），这里必须抓得住
        assertEquals(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class,
                org.springframework.test.util.ReflectionTestUtils.getField(general, "rejectedExecutionHandler").getClass(),
                "队列满必须 CallerRuns（宁可降速不可丢任务）");
        assertEquals(Boolean.TRUE,
                org.springframework.test.util.ReflectionTestUtils.getField(general, "waitForTasksToCompleteOnShutdown"),
                "关停必须等待在途任务");
        assertEquals(10000, ((Number) org.springframework.test.util.ReflectionTestUtils.getField(general, "awaitTerminationMillis")).intValue(),
                "general 池关停等待 10s（内部以毫秒存储）");

        // import 池：core2/max4/queue100，关停等待 30s
        assertEquals(2, importPool.getCorePoolSize(), "import 池 core 必须是 2");
        assertEquals(4, importPool.getMaxPoolSize(), "import 池 max 必须是 4");
        assertEquals(100, importPool.getQueueCapacity(), "import 池队列必须 100");
        assertEquals(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class,
                org.springframework.test.util.ReflectionTestUtils.getField(importPool, "rejectedExecutionHandler").getClass(),
                "import 池队列满同样 CallerRuns");
        assertEquals(Boolean.TRUE,
                org.springframework.test.util.ReflectionTestUtils.getField(importPool, "waitForTasksToCompleteOnShutdown"),
                "import 池关停必须等待在途任务");
        assertEquals(30000, ((Number) org.springframework.test.util.ReflectionTestUtils.getField(importPool, "awaitTerminationMillis")).intValue(),
                "import 池关停等待 30s（内部以毫秒存储）");
    }
}
