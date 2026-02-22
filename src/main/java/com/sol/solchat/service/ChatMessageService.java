package com.sol.solchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.solchat.config.handler.WebSocketSessionManager;
import com.sol.solchat.domain.ChatParticipant;
import com.sol.solchat.domain.Message;
import com.sol.solchat.dto.ChatMessage;
import com.sol.solchat.dto.ChatMessageResponse;
import com.sol.solchat.dto.RelayRequest;
import com.sol.solchat.repository.ChatParticipantRepository;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.repository.MessageRepository;
import com.sol.solchat.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.sol.solchat.constant.ChatConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageService {

    private final MessageRepository messageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final KafkaTemplate<String, ChatMessage> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final WebSocketSessionManager sessionManager;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate(); // HTTP 요청용 (릴레이)
    private final ChatRoomRepository chatRoomRepository;

    private final TransactionTemplate transactionTemplate;

    @Value("${chat.server-id}")
    private String serverId;

    @Value("${chat.internal-secret}")
    private String internalSecret;

    @Value("${chat.kafka.topic}")
    private String kafkaTopic;

    public void processAndPublish(ChatMessage messageDTO) {
        log.info("메시지 처리 시작: Sender={}, Content={}", messageDTO.getSender(), messageDTO.getContent());

        Long newId = idGenerator.nextId();
        messageDTO.setId(String.valueOf(newId));

        Message messageEntity = Message.builder()
                .id(newId)
                .roomId(Long.parseLong(messageDTO.getRoomId()))
                .senderId(Long.parseLong(messageDTO.getSender()))
                .content(messageDTO.getContent())
                .type(messageDTO.getType())
                .build();

        // DB 저장 (DB 저장 구간만 트랜잭션 적용), DB Transaction
        Message savedEntity = transactionTemplate.execute(status -> {
            Message saved = messageRepository.save(messageEntity);
            // 방 존재 확인 (실패 시 롤백)
            chatRoomRepository.findById(saved.getRoomId())
                    .orElseThrow(() -> new IllegalStateException("채팅방 찾을 수 없음"));

            log.info("메시지 RDB 저장 완료: Message ID {}", saved.getId());
            return saved;
        });

        // 채팅방 정렬용 메타데이터 업데이트 (Redis), 네트워크 I/O
//        updateChatRoomMetadataInRedis(savedEntity);

        // 채팅방 정렬용 메타데이터 업데이트 (Redis Lua Script)
        updateChatRoomMetadata(savedEntity.getRoomId(), messageDTO);

        // Kafka 이벤트 발행, 네트워크 I/O, key=roomId
        kafkaTemplate.send(kafkaTopic, messageDTO.getRoomId(), messageDTO);
        log.info("Kafka 발행 완료");
    }

    private void updateChatRoomMetadataInRedis(Message message) {
        String key = REDIS_CHATROOM_METADATA_PREFIX + message.getRoomId();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(KEY_LAST_MESSAGE, message.getContent());
        metadata.put(KEY_LAST_SENT_AT, message.getSentAt().toString());
        metadata.put(KEY_LAST_SENDER_ID, message.getSenderId().toString());
        metadata.put(KEY_LAST_MESSAGE_ID, message.getId().toString());

        redisTemplate.opsForHash().putAll(key, metadata);

        // 변경된 방만 골라서 DB에 업데이트 하기 위함.
        redisTemplate.opsForSet().add(REDIS_CHATROOM_DIRTY_KEYS, message.getRoomId().toString());
    }

    /**
     * 1. 원자성 보장
     * 2. 순서 보장 (네트워크 문제로 메시지 100보다 99가 늦게 도착했을 때, 덮어쓰지 않게 막아줌)
     * 3. DB 부하 감소 (SADD를 통해 변경된 방만 따로 관리(스케줄러))
     */
    private static final String UPDATE_METADATA_SCRIPT =
        "local currentId = redis.call('HGET', KEYS[1], 'lastMessageId') " +
        "local newId = ARGV[3] " +
        "local shouldUpdate = false " +

        // 1. 데이터가 없으면 무조건 업데이트
        "if (not currentId) or (currentId == false) then " +
        "   shouldUpdate = true " +
        "else " +
        // 2. 문자열 길이 비교
        "   local lenCurrent = string.len(currentId) " +
        "   local lenNew = string.len(newId) " +
        "   if lenNew > lenCurrent then " +
        "       shouldUpdate = true " +
        // 3. 사전 순 비교
        "   elseif lenNew == lenCurrent and newId > currentId then " +
        "       shouldUpdate = true " +
        "   end " +
        "end " +

        "if shouldUpdate then " +
        "   redis.call('HSET', KEYS[1], 'lastMessage', ARGV[1]) " +
        "   redis.call('HSET', KEYS[1], 'lastSentAt', ARGV[2]) " +
        "   redis.call('HSET', KEYS[1], 'lastMessageId', newId) " + // newId 저장
        "   redis.call('SADD', KEYS[2], ARGV[4]) " +
        "   return 1 " + // 성공 (1L)
        "else " +
        "   return 0 " + // 실패/무시 (0L)
        "end";
    private final RedisScript<Long> updateScript = new DefaultRedisScript<>(UPDATE_METADATA_SCRIPT, Long.class);

    /**
     * Redis 메타데이터 업데이트 (Lua Script + Snowflake Timestamp)
     * - Race Condition 방지 (Lua Script)
     */
    public Long updateChatRoomMetadata(Long roomId, ChatMessage message) {
        String metadataKey = REDIS_CHATROOM_METADATA_PREFIX + roomId;
        String dirtyKeys = REDIS_CHATROOM_DIRTY_KEYS;
        long createdAt = idGenerator.extractTimestamp(Long.parseLong(message.getId()));

        Object[] args = new Object[] {
                message.getContent(),       // ARGV[1]: 내용
                String.valueOf(createdAt),  // ARGV[2]: 보낸 시간
                message.getId(),            // ARGV[3]: 메시지 ID
                String.valueOf(roomId)      // ARGV[4]: roomId
        };

        return stringRedisTemplate.execute(updateScript, List.of(metadataKey, dirtyKeys), args);
    }
    /**
     * 채팅방 메시지 페이징 조회
     * @param roomId 채팅방 아이디
     * @param page 페이지 번호 (0부터)
     * @param size 페이지 크기
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getChatMessages(Long roomId, int page, int size) {

        // 페이징 정보 생성 (페이지 번호, 크기)
        Pageable pageable = PageRequest.of(page, size);

        Slice<Message> messageSlice = messageRepository.findByRoomIdOrderBySentAtDesc(roomId, pageable);

        return messageSlice.stream()
                .map(ChatMessageResponse::new)
                .collect(Collectors.toList());
    }

    // Kafka Consumer가 호출할 메서드 (메시지 배달!)
    public void deliverToParticipants(ChatMessage messageDTO) {
        Long roomId = Long.parseLong(messageDTO.getRoomId());
        long senderId = Long.parseLong(messageDTO.getSender());

        // 참여자 전체 조회
        List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomId(roomId);
        for (ChatParticipant participant : participants) {
            Long targetUserId = participant.getUserId();

            if (!targetUserId.equals(senderId)) {
                sendMessageToUser(targetUserId, messageDTO);
            }
        }
    }

    // 메시지 전송 메서드: 로컬 + 리모트 서버 릴레이
    public void sendMessageToUser(Long userId, ChatMessage message) {
        Set<WebSocketSession> localSessions = sessionManager.getSessions(userId);
        // 내 서버 메모리에 유저 세션이 있는지 확인
        if (localSessions != null && !localSessions.isEmpty()) {
            // 내 서버에 있음 -> 직접 전송
            sendMessageToLocalUser(userId, message);
        } else {
            // 내 서버에 없음 -> Redis 조회 및 Relay
            String targetServerId = (String) redisTemplate.opsForValue().get(REDIS_USER_SERVER_KEY + userId);
            if (targetServerId != null) {
                relayMessageToOtherServer(targetServerId, userId, message);
            } else {
                log.info("유저가 접속 상태가 아님 (Offline). Push 알림 발송 대상.");
                // TODO
                /**
                 * 1. 앱을 끄거나 로그아웃 한 경우
                 * 2. 백그라운드 상태
                 * 3. 아예 유저가 아닌 경우 (탈퇴한 회원, 데이터 꼬임 등으로 실제 존재하지 않는 userId가 들어온 경우)
                 * 4. Redis 키 만료
                 * FCM 토큰 유무 확인하여 3번 걸러내고,
                 * 4번..
                 * - 유저가 offline 인데 Redis 키 만료 -> 정상 (별도 처리 필요 없을 듯)
                 * - 유저가 Online 인데 Redis 키 만료 -> 비정상 (모니터링 후 TTL 연장!!!!)
                 */
            }
        }
    }

    public void sendMessageToLocalUser(Long userId, ChatMessage message) {
        Set<WebSocketSession> sessions = sessionManager.getSessions(userId);
        if (sessions == null) return;

        try {
            TextMessage textMessage = new TextMessage(objectMapper.writeValueAsString(message));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            log.error("메시지 전송 실패: userId={}", userId, e);
        }
    }

    private void relayMessageToOtherServer(String targetServerId, Long targetUserId, ChatMessage message) {
        String url = "http://" + targetServerId + "/internal/chat/relay";
        try {
            RelayRequest request = new RelayRequest(targetUserId, message);
            // 헤더에 시크릿 키 담기 (내부 통신 전용 시크릿 키 Shared Secret)
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Secret", internalSecret);
            HttpEntity<RelayRequest> entity = new HttpEntity<>(request, headers);

            restTemplate.postForObject(url, entity, Void.class);
            log.info("Relay 성공: {} -> {}", serverId, targetServerId);
        } catch (Exception e) {
            log.error("Relay 실패: target={}, error={}", targetServerId, e.getMessage());
            // TODO FCM Push or circuit breaker
        }
    }
}
