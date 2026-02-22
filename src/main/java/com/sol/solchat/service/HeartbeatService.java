package com.sol.solchat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static com.sol.solchat.constant.ChatConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final RedisTemplate<String, Object> redisTemplate;

    public void renewSession(Long userId) {
        String sessionKey = REDIS_ACTIVE_SESSION_KEY_PREFIX + userId;
        String serverKey = REDIS_USER_SERVER_KEY + userId;

        // 활성 세션 목록(Set) TTL 연장
        Boolean sessionExtended = redisTemplate.expire(sessionKey, Duration.ofMinutes(SESSION_TTL_MINUTES));

        // 서버 위치 정보(String) TTL 연장
        Boolean serverExtended = redisTemplate.expire(serverKey, Duration.ofMinutes(SESSION_TTL_MINUTES));

        if (Boolean.TRUE.equals(sessionExtended) && Boolean.TRUE.equals(serverExtended)) {
            log.debug("Heartbeat success: userId={}", userId);
        } else {
            // 이미 만료됨.. -> 클라이언트에서 소켓 끊고 다시 연결하도록 유도
            log.warn("Heartbeat failed (Key not found): userId={}", userId);
        }
    }
}
