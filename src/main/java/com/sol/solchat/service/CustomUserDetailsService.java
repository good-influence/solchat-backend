package com.sol.solchat.service;

import com.sol.solchat.config.security.UserPrincipal;
import com.sol.solchat.domain.User;
import com.sol.solchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    /*
        Spring Security
        DB에서 사용자 이름 기반으로 사용자 상세 정보 로드
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1. DB에서 사용자 정보 조회
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));

        // 2. 조회한 User 엔티티 UserPrincipa 객체로 변환 및 반환
        return new UserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPassword()
        );
    }
}
