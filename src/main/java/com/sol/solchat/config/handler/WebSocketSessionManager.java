package com.sol.solchat.config.handler;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketSessionManager {

    // Key: UserId, Value: 해당 유저의 세션 목록 (멀티 디바이스 지원)
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    // 세션 등록
    public void addSession(Long userId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    // 특정 유저의 모든 세션 조회
    public Set<WebSocketSession> getSessions(Long userId) {
        return userSessions.get(userId);
    }

    // 세션 제거
    public void removeSession(Long userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            // 더 이상 해당 유저의 세션이 없으면 Map에서 Key 삭제
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }
    }

    // 서버 종료 시 호출되어 연결된 세션 우아하게 종료
    @PreDestroy
    public void gracefulShutdown() {
        log.info("🌈Graceful Shutdown 시작: 연결된 세션 정리 중...");

        int count = 0;
        int batchSize = 3000;   // 한 번에 끊을 세션 수

        // 모든 유저의 세션 순회
        for (Set<WebSocketSession> sessions : userSessions.values()) {
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.SERVICE_RESTARTED);
                    }
                } catch (IOException e) {
                    log.error("세션 종료 중 오류 발생: {}", session.getId(), e);
                }

                count++;

                if (count % batchSize == 0) {
                    try {
                        log.info("Graceful Shutdown: {}개 세션 종료 완료. 잠시 대기...", count);
                        Thread.sleep(1000); // 1초 대기
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Shutdown 대기 중 인터럽트", e);
                    }
                }
            }
            log.info("🌈Graceful Shutdown 완료: 총 {}개 세션 종료됨.", count);
            userSessions.clear(); // 메모리 정리
        }
    }
}
