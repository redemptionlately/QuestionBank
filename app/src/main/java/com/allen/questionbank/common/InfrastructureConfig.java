package com.allen.questionbank.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class InfrastructureConfig implements AsyncConfigurer {

    /**
     * 导入专用池：ImportJobWorker 按名引用（{@code @Async("importTaskExecutor")}）。
     * 队列满时 CallerRunsPolicy：提交线程（HTTP 线程）自己执行——导入体量小（教师上传 PDF），
     * 宁可延迟不可丢任务；若未来导入吞吐变大，应改为 AbortPolicy + 控制器返回 503。
     * 关停时等待在途任务完成（最多 30s），避免强杀导致任务永久停在 PROCESSING。
     */
    @Bean(name = "importTaskExecutor")
    ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("import-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 通用 @Async 默认池（由 AsyncConfigurer 指定）。此前默认池被错误绑定到 importTaskExecutor：
     * 任何新增的 @Async 都会与导入任务挤占同一个 core=2 的池，导入高峰会饿死其他异步逻辑。
     * 池按职责分离——导入有导入的容量，其余异步任务走这里，互不拖累。
     */
    @Bean(name = "generalTaskExecutor")
    ThreadPoolTaskExecutor generalTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("general-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() { return generalTaskExecutor(); }

    @Bean
    RequestMetrics requestMetrics() { return new RequestMetrics(); }

}
