package com.sol.solchat.util;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IdGenerator {

    // 싱글톤으로 관리
    private Snowflake snowflake;

    // 서버(머신)의 고유 번호
    @Value("${snowflake.node-id}")
    private long nodeId;

    // 기준 시간 (밀리초 단위), 프로젝트 시작일 근처로 잡으면 ID 길이 줄어듦
    @Value("${snowflake.custom-epoch}")
    private long customEpoch;

    @PostConstruct
    public void init() {
        this.snowflake = new Snowflake(nodeId, customEpoch);
    }

    public Long nextId() {
        return snowflake.nextId();
    }

    /**
     * Snowflake ID에서 생성시간 추출
     * @param id
     * @return 밀리초 단위의 Unix Timestamp
     */
    public long extractTimestamp(long id) {
        return snowflake.parse(id)[0];
    }
}
