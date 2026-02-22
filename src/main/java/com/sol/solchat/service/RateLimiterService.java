package com.sol.solchat.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    // RedisConfig에서 등록한 ProxyManager<byte[]> 주입
    private final ProxyManager<byte[]> proxyManager;

    /**
     * 토큰을 소비하여 요청 가능 여부를 확인
     * @param key                   구분 키 (ex. "msg:123", "login:192.168.0.1")
     * @param capacity              버킷의 최대 용량 (한 번에 쌓아둘 수 있는 최대 토큰 수)
     * @param refillTokens          리필 될 토큰 수
     * @param refillDuration        리필 주기 (시간)
     * @return true:요청 허용(토큰 소비 성공), false: 요청 거부 (토큰 부족)
     */
    public boolean tryConsume(String key, long capacity, long refillTokens, Duration refillDuration) {
        // 1. 버킷 정책 설정 (ex. capacity=5, refill=5, duration=1초 -> 1초에 5개씩 토큰이 참 (최대 5개까지)
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens, refillDuration)
                        .build())
                .build();

        // 2. String key를 byte[]로 변환
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        // 3. Redis에서 버킷 정보를 가져오거나, 없으면 새로 생성
        // 여기서 Redis의 Key가 결정 됨 ("msg:userId"를 byte[]로 변환한 값)
        Bucket bucket = proxyManager.builder().build(keyBytes, configSupplier);

        // 4. 토큰 1개를 소비 시도하고 성공 여부 반환
        // 이 순간 Redis 통신 발생
        return bucket.tryConsume(1);
        /*
        * bucket.tryConsume(1)이 호출되면 내부적으로 일어나는 일
        * 1. READ(조회): Redis에 GET msg:userId 명령 보냄
        * - 데이터가 없으면? -> 초기값 (꽉 찬 버킷)을 생성
        * - 데이터가 있으면? -> 저장된 바이너리 데이터(GridBucketState)를 가져와서 해석
        * 2. CALCULATE (계산)
        * - "현재시간 - 마지막 충전 시간"을 계산해서 토큰을 리필
        * - 토큰이 1개 이상 있으면 1개를 줄임 (소비)
        * 3. WRITE (저장)
        * - 갱신된 정보(남은 토큰 수 + 현재 시간)를 다시 바이너리로 만듦
        * - Redis에 SET msg:userId [바이너리 데이터] 명령을 보냄
        * 즉, proxyManager가 Redis랑 통신하는 대리인으로 tryConsume 호출시 이 대리인이 Redis에 가서 msg:userId 칸에다가 데이터를 쓰고 옴
        * */
    }
}
