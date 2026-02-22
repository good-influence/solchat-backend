package com.sol.solchat.controller;

import com.sol.solchat.config.security.UserPrincipal;
import com.sol.solchat.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class HeartbeatController {

    private final HeartbeatService heartbeatService;

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> sendHeartbeat(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        if (userPrincipal == null) {
            return ResponseEntity.status(401).build();
        }

        // 로그인된 유저의 Redis TTL 연장
        heartbeatService.renewSession(userPrincipal.getUserId());

        return ResponseEntity.ok().build();
    }
}
