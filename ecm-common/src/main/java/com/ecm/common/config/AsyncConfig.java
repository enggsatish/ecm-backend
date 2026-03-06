package com.ecm.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables Spring @Async processing for the entire platform.
 * Lives in ecm-common so every module that scans com.ecm.common inherits it.
 *
 * WHY A DEDICATED THREAD POOL:
 * Using the default SimpleAsyncTaskExecutor creates an unbounded new thread per task.
 * Under load this causes thread explosion. A bounded pool with a queue is production-safe.
 *
 * POOL SIZING:
 * Audit writes are lightweight JDBC inserts — I/O bound, not CPU bound.
 * 4 core / 20 max / 200 queue handles high-throughput APIs without starvation.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("audit-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}