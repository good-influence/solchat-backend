package com.sol.solchat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class RateLimiterTest {

    @Autowired
    private RateLimiterService rateLimiterService;

    @Test
    @DisplayName("1초에 5회 제한: 5번은 성공하고 6번째는 실패해야 한다")
    void rateLimiterTest() {
        String testKey = "test:user:123";
        long capacity = 5;
        long refillTokens = 5;
        Duration duration = Duration.ofMinutes(1);

        for (int i = 0; i < 5; i++) {
            boolean allowed = rateLimiterService.tryConsume(testKey, capacity, refillTokens, duration);
            System.out.println((i + 1) + "번째 요청: " + allowed);
            assertThat(allowed).isTrue();
        }

        boolean blocked = rateLimiterService.tryConsume(testKey, capacity, refillTokens, duration);
        System.out.println("6번째 요청(차단 예상): " + blocked);
        assertThat(blocked).isFalse();
    }
}
