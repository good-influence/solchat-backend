package com.sol.solchat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    private Long id;

    // 로그인 ID
    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    // 채팅 닉네임
    @Column(nullable = false)
    private String nickname;

    // 회원가입 시 사용
    public User(Long id, String username, String password, String nickname) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }
}