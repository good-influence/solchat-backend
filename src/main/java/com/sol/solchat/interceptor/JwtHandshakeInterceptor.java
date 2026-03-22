package com.sol.solchat.interceptor;

import com.sol.solchat.util.JwtTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * JWT 인증 인터셉터
 * 핸드셰이크 전에 토큰 검사 및 유저 ID 세션 속성에 저장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenUtil jwtTokenUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {

        // HTTP 요청인지 확인
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest req = servletRequest.getServletRequest();

            // 쿼리 파라미터에서 token 추출
            String token = req.getParameter("token");

            if (token == null) {
                String bearerToken = req.getHeader("Authorization");
                if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                    token = bearerToken.substring(7);
                }
            }
            log.info("[Handshake] Token: {}", token);

            if (token != null && jwtTokenUtil.validateToken(token)) {
                Long userId = jwtTokenUtil.getUserIdFromToken(token);

                attributes.put("userId", userId);

                log.info("[Handshake] 인증 성공. User ID: {}", userId);
                return true; // 연결 승인
            }
        }
        log.error("[Handshake] 인증 실패: 토큰이 없거나 유효하지 않음");
        return false; // 연결 거부
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

    }
}
