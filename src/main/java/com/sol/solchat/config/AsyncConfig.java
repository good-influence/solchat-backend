package com.sol.solchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "chatTaskExecutor")
    public Executor chatTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 기본 스레드 수
        executor.setCorePoolSize(10);

        // 최대 스레드 수
        executor.setMaxPoolSize(50);

        // 대기열 크기
        executor.setQueueCapacity(200);

        // 스레드 이름 접두사 설정
        executor.setThreadNamePrefix("Chat-Thread-");

        // 큐도 꽉 차고 스레드도 꽉 찼을 때는? -> 요청한 스레드가 직접 처리
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        return executor;
    }

}
