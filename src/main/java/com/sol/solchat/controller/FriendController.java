package com.sol.solchat.controller;

import com.sol.solchat.config.security.UserPrincipal;
import com.sol.solchat.dto.FriendRequest;
import com.sol.solchat.dto.FriendResponse;
import com.sol.solchat.service.FriendService;
import com.sol.solchat.service.RateLimiterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;
    private final RateLimiterService rateLimiterService;

    // 내 친구 목록 조회
    @GetMapping
    public ResponseEntity<List<FriendResponse>> getMyFriends(Principal principal) {
        UserPrincipal userPrincipal
                = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();

        return ResponseEntity.ok(friendService.getFriends(userPrincipal.getUserId()));
    }

    // 친구 추가
    @PostMapping
    public ResponseEntity<?> addFriend(Principal principal, @RequestBody @Valid FriendRequest request) {
        UserPrincipal userPrincipal
                = (UserPrincipal) ((UsernamePasswordAuthenticationToken) principal).getPrincipal();
        String friendUsername = request.getUsername();

        // [Rate Limiting 적용]
        // Key: "add_friend:{userId}"
        // 정책: 1분에 최대 10명 추가 가능
        boolean allowed = rateLimiterService.tryConsume(
                "add_friend:" + userPrincipal.getUserId(),
                10,
                10,
                Duration.ofMinutes(1)
        );
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("친구 요청을 너무 자주 보내고 있습니다. 잠시 후 다시 시도해주세요.");
        }
        try {
            friendService.addFriend(userPrincipal.getUserId(), friendUsername);
            return ResponseEntity.ok("친구가 추가되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
