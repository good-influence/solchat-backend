package com.sol.solchat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sol.solchat.dto.AuthRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class RateLimitControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("로그인 시도 1초에 5회 제한 테스트")
    void loginRateLimitTest() throws Exception {
        // 테스트용 로그인 데이터
        AuthRequest request = new AuthRequest();
        request.setUsername("tester01");
        request.setPassword("strongpassword123!");
        String jsonRequest = objectMapper.writeValueAsString(request);

        // 1. 5번은 정상 응답 (로그인 실패 401이나 성공 200이 떠야 함, 429는 안 뜸)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonRequest)
                    .remoteAddress("127.0.0.1"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError("5회 이내인데 차단됨..!!");
                        }
                    });
        }

        // 2. 6번째는 429 Too Many Requests 발생해야 함
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .remoteAddress("127.0.0.1"))
                .andExpect(status().isTooManyRequests()); // 429 확인

        System.out.println("✅ 로그인 Rate Limit 테스트 성공!");
    }
}
