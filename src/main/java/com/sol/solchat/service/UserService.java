package com.sol.solchat.service;

import com.sol.solchat.domain.User;
import com.sol.solchat.dto.AuthRequest;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import com.sol.solchat.util.JwtTokenUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenUtil jwtTokenUtil;
    private final IdGenerator idGenerator;

    // 회원 가입 (비밀번호 암호화 및 DB 저장)
    @Transactional
    public void join(AuthRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new IllegalArgumentException("이미 존재하는 사용자 ID입니다.");
        }

        String encodedPW = passwordEncoder.encode(request.getPassword());

        User newUser = new User(
                idGenerator.nextId(),
                request.getUsername(),
                encodedPW,
                request.getNickname()
        );

        userRepository.save(newUser);
    }

    // 로그인 인증 처리 및 JWT 토큰 발급
    public String login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자 ID 또는 비밀번호가 불일치합니다."));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("사용자 ID 또는 비밀번호가 불일치합니다.");
        }

        return jwtTokenUtil.generateToken(user.getUsername(), user.getId());
    }

    // 유저 조회 (다른 서비스에서 호출용, 테스트 코드 연습)
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다. ID: " + userId));
    }
}
