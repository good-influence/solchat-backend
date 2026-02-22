package com.sol.solchat.controller;

import com.sol.solchat.dto.AuthRequest;
import com.sol.solchat.service.RateLimiterService;
import com.sol.solchat.service.UserService;
import com.sol.solchat.util.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final RateLimiterService rateLimiterService;

    @PostMapping("/join")
    public ResponseEntity<String> join(@RequestBody @Valid AuthRequest request, HttpServletRequest servletRequest) {
        String clientIp = ClientIpUtils.getClientIp(servletRequest);
        // [Rate Limiting 적용]
        // Key: "join:{IP주소}"
        // 정책: 한 IP당 1시간에 5번만 가입 시도 가능 (봇 방지)
        boolean allowed = rateLimiterService.tryConsume(
                "join:" + clientIp,
                5,
                5,
                Duration.ofHours(1)
        );
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("단시간에 너무 많은 계정을 생성했습니다. 나중에 다시 시도해주세요.");
        }
        try {
            userService.join(request);
            return ResponseEntity.ok("회원가입이 성공적으로 완료되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody @Valid AuthRequest request, HttpServletRequest servletRequest) {
        String clientIp = ClientIpUtils.getClientIp(servletRequest);
        // [Rate Limiting 적용]
        // Key: "auth:{IP}", 용량: 5, 리필: 1분에 5개
        boolean allowed = rateLimiterService
                .tryConsume("auth:" + clientIp, 5, 5, Duration.ofMinutes(1));
        if (!allowed) {
            return ResponseEntity.status(429).body(Map.of("message", "너무 많은 로그인 시도가 감지 되었습니다. 잠시 후 다시 시도해주세요."));
        }

        Map<String, String> response = new HashMap<>();
        try {
            String jwt = userService.login(request.getUsername(), request.getPassword());
            response.put("token", jwt);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());

            return ResponseEntity.badRequest().body(response);
        }
    }
}
