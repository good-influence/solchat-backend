package com.sol.solchat.service;

import com.sol.solchat.domain.*;
import com.sol.solchat.dto.ChatRoomResponse;
import com.sol.solchat.repository.ChatParticipantRepository;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.repository.MessageRepository;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.sol.solchat.constant.ChatConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final IdGenerator idGenerator;

    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate transactionTemplate;

    private final UserService userService;

    /**
     * 1:1 채팅방 생성을 위한 채팅방 고유 키 생성
     * 사용자 ID가 작은 쪽을 앞에 두어 순서를 정규화 -> 같은 1:1 채팅방에 대해 항상 동일한 키 사용
     */
    public static String getDirectChatKey(Long userId1, Long userId2) {
        Long smallerId = Math.min(userId1, userId2);
        Long largerId = Math.max(userId1, userId2);

        return String.format("%d-%d", smallerId, largerId);
    }

    /**
     *  1:1 채팅방이 없는 경우 생성 (DB UNIQUE 제약 조건 활용)
     */
    @Transactional
    public ChatRoomCreationResult createDirectChatRoomIfNotExist(Long currentUserId, Long targetUserId) {

        userRepository.findById(targetUserId)
            .orElseThrow(() -> new IllegalArgumentException("타겟 사용자(ID: " + targetUserId + ") 를 찾을 수 없음"));
        // 다른 서비스 호출 테스트 (추후 삭제)
        // Service A(ChatRoom) -> Service B (User) 호출 발생
        // User targetUser = userService.getUserById(targetUserId);

        final String chatKey = getDirectChatKey(currentUserId, targetUserId);

        List<Long> existingRoomIds = chatParticipantRepository.findExistingDirectChatRoomIds(currentUserId, targetUserId);

        if (!existingRoomIds.isEmpty()) {
            ChatRoom existingRoom = chatRoomRepository.findById(existingRoomIds.get(0))
                    .orElseThrow(() -> new IllegalStateException("참여자 목록에 있지만 ChatRoom이 존재하지 않음"));

            return new ChatRoomCreationResult(existingRoom, false);
        }

        try {
            ChatRoom newRoom = new ChatRoom(
                idGenerator.nextId(),
                String.format("DM:%s", chatKey),
                "DIRECT",
                chatKey
            );

            ChatRoom savedRoom = chatRoomRepository.save(newRoom);

            ChatParticipant participant1 = new ChatParticipant(idGenerator.nextId(), savedRoom.getId(), currentUserId);
            ChatParticipant participant2 = new ChatParticipant(idGenerator.nextId(), savedRoom.getId(), targetUserId);

            chatParticipantRepository.save(participant1);
            chatParticipantRepository.save(participant2);

            log.info("새 1:1 채팅방 생성 완료 Room ID {}", savedRoom.getId());

            return new ChatRoomCreationResult(savedRoom, true);
        } catch (DataIntegrityViolationException e) {
            // [경쟁 발생] 다른 스레드가 먼저 생성에 성공한 경우: DB 고유 키 위반 예외 발생
            log.warn("채팅방 생성 중 DataIntegrityViolationException 발생 (동시 생성 경쟁): {}", chatKey);
            throw new ChatRoomCreationConflictException("동시 생성 경쟁 발생!!! 재조회 필요.");
        }
    }

    /**
     * 사용자가 참여 중인 채팅방 목록 조회 (+ 페이징)
     */
    public Slice<ChatRoomResponse> getChatRooms(Long userId, Pageable pageable) {
        // 내 채팅방 목록 조회
        Slice<ChatRoom> chatRoomSlice = chatRoomRepository.findChatRoomsByUserId(userId, pageable);

        // 방 ID 목록 추출
        List<Long> roomIds = chatRoomSlice.stream().map(ChatRoom::getId).toList();

        // 내 참여 정보 한 번에 조회
        List<ChatParticipant> participants = chatParticipantRepository.findAllByUserIdAndChatRoomIdIn(userId, roomIds);

        // key: RoomId, value: Participant
        Map<Long, ChatParticipant> participantMap = participants.stream()
                .collect(Collectors.toMap(ChatParticipant::getChatRoomId, p -> p));

        // 파트너 ID 목록 추출
        List<Long> partnerIds = chatRoomSlice.stream()
                .filter(chatRoom -> "DIRECT".equals(chatRoom.getType()))
                .map(room -> parsePartnerId(room.getChatKey(), userId))
                .toList();

        // 파트너 정보 한 번에 조회
        List<User> partners = userRepository.findAllById(partnerIds);

        Map<Long, String> partnerNicknameMap = partners.stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));

        return chatRoomSlice.map(room -> {
            String roomTitle = room.getName();
            if ("DIRECT".equals(room.getType())) {
                Long partnerId = parsePartnerId(room.getChatKey(), userId);
                roomTitle = partnerNicknameMap.getOrDefault(partnerId, "알 수 없는 사용자");
            }
            // 이 방에서 나의 참여 정보 찾기
            ChatParticipant myParticipant = participantMap.get(room.getId());
            Long readMsgId = myParticipant.getLastReadMessageId();

            // 읽은 메시지 ID 레디스에서 조회
            String readPosKey = REDIS_ROOM_READ_POS_PREFIX + room.getId();
            String redisReadIdStr = (String) redisTemplate.opsForHash().get(readPosKey, userId.toString());

            if (StringUtils.hasText(redisReadIdStr)) {
                long redisReadId = Long.parseLong(redisReadIdStr);
                if (redisReadId > readMsgId) {
                    readMsgId = redisReadId;    // Redis 정보가 더 최신이면 덮어쓰기
                }
            }

            // 안 읽은 메시지 수 계산
            int unreadCount = messageRepository.countByRoomIdAndIdGreaterThan(room.getId(), readMsgId);

            // Redis Write-Behind 를 적용하였으므로, DB 업데이트가 지연되더라도 실시간 갱신된 마지막 메시지 보여주기
            String key = REDIS_CHATROOM_METADATA_PREFIX + room.getId();
            String lastMessage = (String) redisTemplate.opsForHash().get(key, KEY_LAST_MESSAGE);
            String lastSentAt = (String) redisTemplate.opsForHash().get(key, KEY_LAST_SENT_AT);

            if (StringUtils.hasText(lastMessage) && StringUtils.hasText(lastSentAt)) {
                return ChatRoomResponse.builder()
                        .id(room.getId())
                        .title(roomTitle)
                        .type(room.getType())
                        .lastMessage(lastMessage)
                        .lastSentAt(Long.parseLong(lastSentAt))
                        .unreadCount(unreadCount)
                        .build();
            } else {
                return ChatRoomResponse.from(room, roomTitle, unreadCount);
            }
        });
    }

    private Long parsePartnerId(String chatKey, Long myUserId) {
        // chatKey 포맷: "smallerId-largerId" ("1-2");
        String[] ids = chatKey.split("-");
        long id1 = Long.parseLong(ids[0]);
        long id2 = Long.parseLong(ids[1]);

        return myUserId.equals(id1) ? id2 : id1;
    }

    public boolean isParticipant(Long chatRoomId, Long userId) {
        return chatParticipantRepository.existsByChatRoomIdAndUserId(chatRoomId, userId);
    }

    // 메시지 읽음 처리
//    @Transactional
    public void markAsRead(Long chatRoomId, Long userId) {
        chatParticipantRepository.findByChatRoomIdAndUserId(chatRoomId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅방에 참여하고 있지 않음"));

        // DB 직접 업데이트 -> Redis Write-Behind 로 수정함
//        messageRepository.findTopByRoomIdOrderByIdDesc(chatRoomId)
//                .ifPresent(latestMessage -> {
//                    participant.updateLastReadMessageId(latestMessage.getId());
//                });
        String roomKey = REDIS_CHATROOM_METADATA_PREFIX + chatRoomId;
        // Redis에서 'lastMessageId' 조회
        Object redisLastMsgIdObj = redisTemplate.opsForHash().get(roomKey, KEY_LAST_MESSAGE_ID);
        long lastMessageId;
        if (redisLastMsgIdObj != null) {
            lastMessageId = Long.parseLong((String) redisLastMsgIdObj);
        } else {
            // (Fallback) Redis에 없으면 DB에서 조회
            lastMessageId = messageRepository.findTopByRoomIdOrderByIdDesc(chatRoomId)
                    .map(Message::getId)
                    .orElse(0L);
        }

        // Redis에 내 읽은 위치 저장
        String readPosKey = REDIS_ROOM_READ_POS_PREFIX + chatRoomId;
        redisTemplate.opsForHash().put(readPosKey, userId.toString(), Long.toString(lastMessageId));

        // 동기화 대기열 추가 (DB 저장은 배치루)
        redisTemplate.opsForSet().add(REDIS_ROOM_READ_POS_DIRTY_KEYS, chatRoomId.toString());
    }

    /**
     *  멤버 구성이 같은 기존 단체방 조회
     *  @return 찾은 방의 ID (없으면 null)
     */
    public Long findExistingGroupChatId(Long creatorId, List<Long> targetUserIds) {
        // 전체 멤버리스트 생성 (본인 + 초대 멤버들)
        List<Long> allMembers = new ArrayList<>(targetUserIds);
        if (!allMembers.contains(creatorId)) {
            allMembers.add(creatorId);
        }
        // 혹시 모를 중복 제거
        allMembers = allMembers.stream()
                .distinct()
                .collect(Collectors.toList());

        // allMembers과 일치하는 방 조회
        List<ChatRoom> existingRooms = chatRoomRepository.findGroupChatByExactMembers(allMembers, allMembers.size());

        // 가장 최근 만들어진 방 아이디 반환
        if (!existingRooms.isEmpty()) {
            return existingRooms.get(0).getId();
        }

        return null;
    }

    /**
     *  단체 채팅방 생성 (신규 생성 or 같은 멤버로 채팅방이 있지만, 클라이언트 요청으로 추가 생성)
     *  UUID 사용하여 중복 검사 없이 새로운 방 생성
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChatRoom createGroupChat(Long creatorId, String title, List<Long> targetUserIds) {
        // 생성할 방의 멤버들 정리
        List<Long> allMembers = new ArrayList<>(targetUserIds);
        if (!allMembers.contains(creatorId)) {
            allMembers.add(creatorId);
        }

        // 혹시 모를 중복 제거
        allMembers = allMembers.stream()
                .distinct()
                .collect(Collectors.toList());

        // UUID로 고유 키 생성 (1:1 채팅은 중복 미허용, 단체방은 중복 하용)
        String chatKey = UUID.randomUUID().toString();
        Long roomId = idGenerator.nextId();
        ChatRoom chatRoom = new ChatRoom(
                roomId,
                (title != null && !title.isEmpty()) ? title : "단체 채팅방",
                "GROUP",
                chatKey
        );

        // 채팅방 멤버(참여자)
        List<ChatParticipant> participants = allMembers.stream()
                .map(userId -> new ChatParticipant(idGenerator.nextId(), roomId, userId))
                .toList();

        // hibernate batch_size (100) -> mysql 드라이버는 rewriteBatchedStatements=true 설정으로 인해 단일 쿼리로 날림
        // 해당 구간만 DB Transaction 사용
        ChatRoom savedRoom = transactionTemplate.execute(status -> {
            ChatRoom saved = chatRoomRepository.save(chatRoom);
            // 참여자 저장 (Batch Insert)
            chatParticipantRepository.saveAll(participants);
            return saved;
        });


        log.info("새로운 단체 채팅방 생성 완료: ID={}, 멤버수={}", savedRoom.getId(), allMembers.size());
        return savedRoom;
    }

    // 최적화 1단계
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncReadStatusForRoom(Long roomId) {
        String key = REDIS_ROOM_READ_POS_PREFIX + roomId;
        Map<Object, Object> userReadPositions = redisTemplate.opsForHash().entries(key);

        for (Map.Entry<Object, Object> entry : userReadPositions.entrySet()) {
            long userId = Long.parseLong((String) entry.getKey());
            long lastReadMsgId = Long.parseLong((String) entry.getValue());

            // 유저 한명당 SELECT + UPDATE ... 단체방 100명 -> 200번!!!
//            chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
//                    .ifPresent(p -> p.updateLastReadMessageId(lastReadMsgId));

            // 최적화 1단계 한 방에 업데이트 (1 쿼리), 그래도 100명일때 쿼리가 100번임
            chatParticipantRepository.updateReadStatus(roomId, userId, lastReadMsgId);
        }
    }

    // 최적화 2단계
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncBulkReadStatusForRoom(Long roomId) {
        String key = REDIS_ROOM_READ_POS_PREFIX + roomId;
        Map<Object, Object> userReadPositions = redisTemplate.opsForHash().entries(key);

        if (userReadPositions.isEmpty()) return;

        // DB에서 해당 방의 모든 참여자를 한 번에 조회 (SELECT 1회)
        List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomId(roomId);

        // 메모리에서 매칭 및 값 변경
        for (ChatParticipant p : participants) {
            String userIdStr = p.getUserId().toString();
            if (userReadPositions.containsKey(userIdStr)) {
                long newReadId = Long.parseLong((String) userReadPositions.get(userIdStr));

                if (newReadId > p.getLastReadMessageId()) {
                    p.updateLastReadMessageId(newReadId);
                }
            }
        }
        // 메서드 종료 시 JPA가 변경 된 엔티티 감지 -> UPDATE 쿼리, application.yml batch_size 만큼 한 방에!
    }
}
