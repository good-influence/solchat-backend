package com.sol.solchat.service;

import com.sol.solchat.domain.User;
import com.sol.solchat.dto.AuthRequest;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import com.sol.solchat.util.JwtTokenUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @InjectMocks // 가짜 객체들을 주입받을 진짜 테스트 대상
    private UserService userService;

    // userService가 필요로 하는 가짜 부품들 (Mock)
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenUtil jwtTokenUtil;
    @Mock private IdGenerator idGenerator;


    /**
     * Given-When-Then
     * 1. Given (준비): 이런 상황이 주어졌을 때
     * 2. When (실행): 이 메서드를 실행하면
     * 3. Then (검증): 이런 결과가 나와야 한다.
     */
    @Test
    @DisplayName("회원가입 성공: 비밀번호가 암호화되어 저장되어야 한다")
    void joinSuccess() {
        // 1. Given (상
        // 황 설정): 사용자가 보낸 요청 데이터 준비
        AuthRequest request = new AuthRequest();
        request.setUsername("solchat_user");
        request.setPassword("realPassword123");
        request.setNickname("솔챗");

        // 중복 아님 연출
        given(userRepository.findByUsername("solchat_user")).willReturn(Optional.empty());

        given(passwordEncoder.encode("realPassword123")).willReturn("encoded_pw");

        given(idGenerator.nextId()).willReturn(100L);

        userService.join(request);

        verify(userRepository).save(argThat(user ->
                user.getPassword().equals("encoded_pw") &&
                user.getUsername().equals("solchat_user")));

    }

    @Test
    @DisplayName("회원가입 실패: 이미 존재하는 아이디면 예외가 터져야 한다")
    void joinFailDuplicate() {
        // 1. Given
        AuthRequest request = new AuthRequest();
        request.setUsername("exist_user");
        request.setPassword("1234");

        User existingUser = new User(1L, "exist_user", "pw", "nick");

        given(userRepository.findByUsername("exist_user")).willReturn(Optional.of(existingUser));

        // 2. When & 3. Then (실행과 동시 검증)
        assertThatThrownBy(() -> userService.join(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재하는");
    }

    @Test
    @DisplayName("로그인 성공: 아이디/비밀번호가 맞으면 토큰을 반환한다")
    void loginSuccess() {
        // 1. Given
        String username = "test_user";
        String rawPassword = "password123";
        String encodedPassword = "encoded_password123";

        // DB에 저장되어 있는 유저 상황 연출
        User user = new User(1L, username, encodedPassword, "nick");

        given(userRepository.findByUsername(username)).willReturn(Optional.of(user));

        // 암호화된 비밀번호와 입력한 비밀번호가 일치한다고 설정
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(true);

        given(jwtTokenUtil.generateToken(username, 1L)).willReturn("fake-jwt-token");

        // 2. When
        String token = userService.login(username, rawPassword);

        // 3. Then
        assertThat(token).isEqualTo("fake-jwt-token");
    }
}
