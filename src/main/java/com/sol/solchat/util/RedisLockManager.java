package com.sol.solchat.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 락 획득 시도 (SETNX)
     * @param lockKey 잠금 키
     * @param lockValue 락 소유자 식별 값 (스레드 ID, UUID등..)
     * @return 락 획득 성공 여부 (boolean)
     */
    public boolean acquireLock(String lockKey, String lockValue) {
        log.info("Lock 획득 시도: {}", lockKey);

        // setIfAbsent: 키가 존재하지 않을 때만 값 설정 (SETNX)
        // 설정 성공시 true 반환, 만료시간(TTL) 설정
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, LOCK_TIMEOUT);

        return success != null && success;
    }

    /**
     * 락 해제 (잠금 해제)
     * 획득 시 사용한 lockValue와 일치할 때만 해제 (다른 스레드가 해제하는 것 방지)
     * @param lockKey 잠금 키
     * @param lockValue 락 소유자 식별 값
     */
    public void releaseLock(String lockKey, String lockValue) {
         String currentValue = (String) redisTemplate.opsForValue().get(lockKey);

         // 락 소유자가 본인일 때만 해제
         if (lockValue != null && lockValue.equals(currentValue)) {
             // Redis 에서 키 삭제하여 락 해제
             redisTemplate.delete(lockKey);
             log.info("Lock 해제 완료: {}", lockKey);
         } else if (currentValue == null) {
             log.warn("Lock 해제 시도 실패: 이미 만료되었거나 해제된 락: {}", lockKey);
         } else {
             log.error("Lock 해제 권한 없음: Lock Key {}의 소유자 불일치", lockKey);
         }
    }
}
