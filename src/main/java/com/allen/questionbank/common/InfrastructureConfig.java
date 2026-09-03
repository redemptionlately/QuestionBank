package com.allen.questionbank.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class InfrastructureConfig implements AsyncConfigurer {
    @Bean(name = "importTaskExecutor")
    ThreadPoolTaskExecutor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("import-worker-");
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() { return importTaskExecutor(); }

    @Bean
    RequestMetrics requestMetrics() { return new RequestMetrics(); }

}
