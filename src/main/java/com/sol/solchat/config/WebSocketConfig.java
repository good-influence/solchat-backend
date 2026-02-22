package com.sol.solchat.config;

import com.sol.solchat.handler.ChatWebSocketHandler;
import com.sol.solchat.interceptor.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*")
                .addInterceptors(jwtHandshakeInterceptor);
    }

    // 웹 소켓 컨테이너 설정 (타임아웃 및 버퍼 사이즈)
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();

        // 세션 유휴 시간, 설정한 시간동안 아무런 메시지가 오고 가지 않으면 연결을 끊어버림
        container.setMaxSessionIdleTimeout(60000L * 30);

        // 메시지 버퍼 크기, 너무 큰 메시지 공격 방지
        container.setMaxTextMessageBufferSize(8192); // 8KB (텍스트)
        container.setMaxBinaryMessageBufferSize(8192);  // 8KB (바이너리)

        return container;
    }
}
