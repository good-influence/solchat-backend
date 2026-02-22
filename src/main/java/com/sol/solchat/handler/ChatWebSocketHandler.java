package com.sol.solchat.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.solchat.config.handler.WebSocketSessionManager;
import com.sol.solchat.dto.ChatMessage;
import com.sol.solchat.service.ChatMessageService;
import com.sol.solchat.service.ChatRoomService;
import com.sol.solchat.service.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.sol.solchat.constant.ChatConstants.*;

@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private final RateLimiterService rateLimiterService;

    @Value("${chat.server-id}")
    private String serverId;

    private final Executor taskExecutor;
    private final ChatRoomService chatRoomService;

    public ChatWebSocketHandler(WebSocketSessionManager sessionManager, ChatMessageService chatMessageService,
                                ObjectMapper objectMapper, RedisTemplate<String, Object> redisTemplate,
                                @Qualifier("chatTaskExecutor") Executor taskExecutor,
                                RateLimiterService rateLimiterService,
                                ChatRoomService chatRoomService) {
        this.sessionManager = sessionManager;
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.taskExecutor = taskExecutor;
        this.rateLimiterService = rateLimiterService;
        this.chatRoomService = chatRoomService;
    }

    // 연결 성공 시
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 인터셉터에서 넣어준 userId
        Long userId = (Long) session.getAttributes().get("userId");

        if (userId != null) {
            sessionManager.addSession(userId, session);
            String sessionKey = REDIS_ACTIVE_SESSION_KEY_PREFIX + userId;
            String serverKey = REDIS_USER_SERVER_KEY + userId;
            // 세션 리스트에 추가
            redisTemplate.opsForSet().add(sessionKey, session.getId());
            redisTemplate.expire(sessionKey, Duration.ofMinutes(SESSION_TTL_MINUTES));
            // 서버 위치 정보 저장
            redisTemplate.opsForValue().set(serverKey, serverId, Duration.ofMinutes(SESSION_TTL_MINUTES));

            log.info("[Connect] User: {}, Server: {} (TTL: {}min)", userId, serverId, SESSION_TTL_MINUTES);
        } else {
            session.close(); // 인증 안 된 유저 차단
        }
    }

    // 메시지 수신 시
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            // TODO 에러 처리
            return;
        }

        // [Rate Limiting 적용]
        // Key: "msg:{userId}", 용량: 5, 리필: 1초에 5개 (=> 초당 5회 제한)
        boolean allowed = rateLimiterService
                .tryConsume("msg:" + userId, 5, 5, Duration.ofSeconds(1));

        if (!allowed) {
            log.warn("Rate limit exceeded for user: {}", userId);
            try {
                // 클라이언트로 에러 메시지 전송 (프론트 처리)
                session.sendMessage(new TextMessage("{\"type\":\"ERROR\", \"message\":\"메시지 전송 제한 걸림\"}"));
            } catch (IOException e) {
                // TODO error
            }
            return; // 처리 중단
        }

        taskExecutor.execute(() -> {
            try {
                String payload = message.getPayload();
                ChatMessage chatMessage = objectMapper.readValue(payload, ChatMessage.class);

                Long verifiedUserId = (Long) session.getAttributes().get("userId");
                if (verifiedUserId == null) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }

                // 해당 채팅방의 참여자가 맞는지 검증 (IDOR 방지)
                long roomId = Long.parseLong(chatMessage.getRoomId());
                if (!chatRoomService.isParticipant(roomId, verifiedUserId)) {
                    log.warn("[보안 경고] 권한 없는 채팅방 접근 시도! userId: {}, roomId: {}", verifiedUserId, roomId);
                    session.sendMessage(new TextMessage("{\"type\":\"ERROR\", \"message\":\"해당 채팅방에 참여하고 있지 않아 메시지를 보낼 수 없습니다.\"}"));
                    return;
                }

                chatMessage.setSender(String.valueOf(verifiedUserId));

                // 서비스 로직 호출 (DB 저장, Kafka 발행 등)
                chatMessageService.processAndPublish(chatMessage);
            } catch (Exception e) {
                log.error("메시지 처리 중 에러 발생", e);
                // TODO 질문 (오류 처리 방법)
            }
        });
    }

    // 연결 해제 시
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        // 1. 로컬 메모리에서 세션 지우기
        sessionManager.removeSession(userId, session);

        // 2. Redis 바구니(Set)에서도 방금 끊어진 특정 세션 ID 지우기
        String sessionKey = REDIS_ACTIVE_SESSION_KEY_PREFIX + userId;
        redisTemplate.opsForSet().remove(sessionKey, session.getId());

        // 3. 남은 세션 확인 후 서버 맵핑 정보 지우기
        Set<WebSocketSession> remainingSessions = sessionManager.getSessions(userId);
        if (remainingSessions == null || remainingSessions.isEmpty()) {
            String savedServer = (String) redisTemplate.opsForValue().get(REDIS_USER_SERVER_KEY + userId);
            if (serverId.equals(savedServer)) {
                redisTemplate.delete(REDIS_USER_SERVER_KEY + userId);
            }
        }
        log.info("[Disconnect] User: {}", userId);
    }
}
