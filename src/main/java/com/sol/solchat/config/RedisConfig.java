package com.sol.solchat.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    /**
     * RedisTemplate Bean 등록, Key와 Value 직렬화 방식 설정
     * 키: StringRedisSerializer 사용하여 문자열로 저장
     * 값: RedisLockManager 에서 String (lockValue) 사용하므로, StringRedisSerializer 사용
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        // Key 직렬화 설정 (문자열)
        redisTemplate.setKeySerializer(new StringRedisSerializer());

        // Value 직렬화 설정 (문자열)
        redisTemplate.setValueSerializer(new StringRedisSerializer());

        // Hash Key 직렬화 설정 (문자열)
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());

        // Hash Value 직렬화 설정 (문자열)
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());

        // RedisTemplate을 사용하기 전에 초기화
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

    /**
     * Bucket4j가 Redis와 통신하기 위한 매니저
     */
    @Bean
    public LettuceBasedProxyManager<byte[]> proxyManager(RedisConnectionFactory redisConnectionFactory) {
        LettuceConnectionFactory lettuceFactory = (LettuceConnectionFactory) redisConnectionFactory;
        RedisClient redisClient = (RedisClient) lettuceFactory.getNativeClient();

        ClientSideConfig clientSideConfig = ClientSideConfig.getDefault()
                .withExpirationAfterWriteStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10))
                );

        return LettuceBasedProxyManager.builderFor(redisClient)
                .withClientSideConfig(clientSideConfig)
                .build();
    }
}
