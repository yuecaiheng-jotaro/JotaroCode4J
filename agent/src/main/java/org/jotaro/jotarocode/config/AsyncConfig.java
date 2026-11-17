package org.jotaro.jotarocode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步执行器配置。
 * <p>
 * 开启 {@code @EnableAsync} 并定义名为 {@code taskExecutor} 的默认线程池，
 * 供 {@link org.jotaro.jotarocode.event.listener.PromptEventListener} 异步处理会话消息事件，
 * 使 HTTP 请求线程可以立即返回，而耗时的 Agent 推理与工具调用在后台线程执行。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 创建 Spring {@code @Async} 默认使用的线程池执行器。
     * 方法名 {@code taskExecutor} 是 Spring 异步机制的默认查找名称，监听器无需额外指定执行器名。
     *
     * @return 异步任务执行器
     */
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心常驻线程数
        executor.setCorePoolSize(4);
        // 最大并发线程数
        executor.setMaxPoolSize(10);
        // 等待队列容量
        executor.setQueueCapacity(100);
        // 异步工作线程名称前缀，便于日志排查
        executor.setThreadNamePrefix("jotaro-");
        executor.initialize();
        return executor;
    }
}
