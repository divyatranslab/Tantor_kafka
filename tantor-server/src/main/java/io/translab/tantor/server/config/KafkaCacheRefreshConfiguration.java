package io.translab.tantor.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class KafkaCacheRefreshConfiguration {
    @Bean(name = "kafkaCacheRefreshExecutor")
    public Executor kafkaCacheRefreshExecutor(
            @Value("${tantor.cache-refresh.pool-size:4}") int poolSize,
            @Value("${tantor.cache-refresh.queue-capacity:128}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, poolSize));
        executor.setMaxPoolSize(Math.max(1, poolSize));
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("kafka-cache-refresh-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
