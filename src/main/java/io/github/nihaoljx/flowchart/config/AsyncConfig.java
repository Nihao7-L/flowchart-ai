package io.github.nihaoljx.flowchart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行装配
 *
 * <p>P0 #2 的第一步：把 {@link
 * io.github.nihaoljx.flowchart.controller.DiagramController}
 * 每请求一条 {@code new Thread()} 的无界裸线程换成受管的
 * {@code ThreadPoolTaskExecutor} —— 裸线程不可复用、不可拒绝、
 * 高并发下直接线程数失控；线程池有界、可在应用关闭时优雅回收。
 *
 * <p>取消标志（onTimeout / onError / onCompletion 置位后由生成循环
 * 检查）属于下一步增量，本步只做可复用线程池这一半，改动最小、
 * 可独立回退。
 */
@Configuration
public class AsyncConfig {

    private static final Logger LOG =
            LoggerFactory.getLogger(AsyncConfig.class);

    /** 生成任务线程池：有界、前缀可溯、优雅关闭 */
    @Bean
    public TaskExecutor generationExecutor() {
        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chartflow-gen-");
        executor.setWaitForTasksToCompleteOnShutdown(
                true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        LOG.info("生成线程池已初始化 "
                + "(core=2 max=8 queue=200)");
        return executor;
    }
}
