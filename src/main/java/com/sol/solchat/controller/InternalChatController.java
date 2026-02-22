package com.sol.solchat.controller;

import com.sol.solchat.dto.RelayRequest;
import com.sol.solchat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/internal/chat")
@RequiredArgsConstructor
public class InternalChatController {

    private final ChatMessageService chatMessageService;

    @Value("${chat.internal-secret}")
    private String internalSecret;

    // 다른 서버로부터 받은 메시지 전송 요청
    @PostMapping("/relay")
    public ResponseEntity<Void> relayMessage(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secret,
            @RequestBody RelayRequest request) {
        // 시크릿 키 검증 실패 시 401 Unauthorized 반환
        if (secret == null || !internalSecret.equals(secret)) {
            log.warn("🚨 [보안 경고] 잘못된 시크릿 키로 내부 API 접근 시도!");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("[Internal] 릴레이 요청 수신: sender={}, roomId={}"
                , request.getMessage().getSender(), request.getMessage().getRoomId());

        // 내 서버에 있는 유저에게 전송
        chatMessageService.sendMessageToLocalUser(request.getTargetUserId(), request.getMessage());

        return ResponseEntity.ok().build();
    }
}
