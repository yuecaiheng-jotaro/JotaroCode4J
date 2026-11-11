package org.jotaro.jotarocode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 打开 {@code @Async}。方法名 {@code taskExecutor} 是 Spring 异步的默认执行器名，
 * {@link org.jotaro.jotarocode.event.listener.PromptEventListener} 不用再指定名字。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pi-agent-");
        executor.initialize();
        return executor;
    }
}
