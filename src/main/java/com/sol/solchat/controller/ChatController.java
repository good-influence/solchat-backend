package com.sol.solchat.controller;

import com.sol.solchat.config.security.UserPrincipal;
import com.sol.solchat.domain.ChatRoom;
import com.sol.solchat.domain.ChatRoomCreationResult;
import com.sol.solchat.dto.*;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.service.ChatMessageService;
import com.sol.solchat.service.ChatRoomCreationConflictException;
import com.sol.solchat.service.ChatRoomService;
import com.sol.solchat.service.RateLimiterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatRoomService chatRoomService;
    private final ChatMessageService chatMessageService;
    private final ChatRoomRepository chatRoomRepository;

    private final RateLimiterService rateLimiterService;

    @PostMapping("/room")
    public ResponseEntity<?> createDirectChatRoom(@RequestBody @Valid ChatRoomRequest request, Principal principal) {
        // 현재 로그인된 사용자 ID 추출
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        Long currentUserId = userPrincipal.getUserId();

        // [Rate Limiting 적용]
        // Key: "create_room:{userId}"
        // 정책: 1분에 최대 5개 생성 가능
        boolean allowed = rateLimiterService.tryConsume(
                "create_room:" + userPrincipal.getUserId(),
                5,
                5,
                Duration.ofMinutes(1));
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                    "채팅방 너무 자주 생성하고 있습니다. 잠시 후 다시 시도해주세요.");
        }

        try {
            ChatRoomCreationResult result = chatRoomService
                    .createDirectChatRoomIfNotExist(currentUserId, request.getTargetUserId());

            return result.newlyCreated()
                    ? ResponseEntity.status(HttpStatus.CREATED).body(result.chatRoom())
                    : ResponseEntity.ok(result.chatRoom());

        } catch (ChatRoomCreationConflictException e) {
            log.warn("동시성 경쟁 발생. 재조회 시작");
            String chatKey = ChatRoomService.getDirectChatKey(currentUserId, request.getTargetUserId());

            ChatRoom chatRoom = chatRoomRepository.findByChatKey(chatKey)
                    .orElseThrow(() -> {
                        log.error("경쟁 후에도 ChatRoom 을 찾을 수 없음...: {}", chatKey);
                        return new IllegalStateException("동시성 경쟁 후 방 찾는 데 실패");
                    });

            return ResponseEntity.ok(chatRoom);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("채팅방 생성 중 알 수 없는 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("채팅방 생성 중 오류 발생");
        }
    }

    @GetMapping("/rooms")
    public ResponseEntity<Slice<ChatRoomResponse>> getMyChatRooms(
            Principal principal,
            @PageableDefault(size = 20) Pageable pageable
            ) {
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        Long currentUserId = userPrincipal.getUserId();

        Slice<ChatRoomResponse> rooms = chatRoomService.getChatRooms(currentUserId, pageable);

        return ResponseEntity.ok(rooms);

    }

    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<?> getRoomMessages(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Principal principal
    ) {
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        Long currentUserId = userPrincipal.getUserId();

        if (!chatRoomService.isParticipant(roomId, currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("해당 채팅방 접근 권한 없음");
        }

        List<ChatMessageResponse> chatMessages = chatMessageService.getChatMessages(roomId, page, size);

        return ResponseEntity.ok(chatMessages);
    }

    // 메시지 읽음 처리 API
    @PostMapping("/rooms/{roomId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable Long roomId,
            Principal principal
    ) {
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        chatRoomService.markAsRead(roomId, userPrincipal.getUserId());

        return ResponseEntity.ok().build();
    }

    /**
     *  단체 채팅방 생성 API
     *  - 기존 채팅방 있을 시 클라이언트 요청에 따라 기존방 반환 또는 새 채팅방 생성
     *  POST /api/chat/group
     */
    @PostMapping("/group")
    public ResponseEntity<?> createGroupChat(
            @RequestBody @Valid GroupChatRequest request, Principal principal
    ) {
        UserPrincipal userPrincipal = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        Long creatorId = userPrincipal.getUserId();

        // 신규생성 or 기존방 조회
        if (!request.isForceCreate()) {
            Long existingRoomId = chatRoomService.findExistingGroupChatId(creatorId, request.getUserIds());

            if (existingRoomId != null) {
                return ResponseEntity.ok(Map.of(
                        "status", "EXIST",
                        "roomId", existingRoomId,
                        "message", "기존 채팅방이 존재합니다."
                ));
            }
        }

        // 강제 생성이거나(기존 방 있는 경우), 새 방 생성 (기존 방 없는 경우)
        ChatRoom newRoom = chatRoomService.createGroupChat(creatorId, request.getTitle(), request.getUserIds());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "status", "CREATED",
                "roomId", newRoom.getId(),
                "room", ChatRoomResponse.from(newRoom, newRoom.getName(), 0)
        ));
    }
}
