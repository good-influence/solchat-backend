package com.sol.solchat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * Kafka 직렬화/역직렬화 및 일반 JSON 처리에 사용될 ObjectMapper Bean 등록
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
