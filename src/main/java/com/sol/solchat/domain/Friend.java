package com.sol.solchat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.security.Principal;

@Entity
@Table(name = "friend",
    uniqueConstraints = {
        // 중복 친구 추가 방지
        @UniqueConstraint(columnNames = {"user_id", "friend_user_id"})
    }
)
@Getter
@NoArgsConstructor
public class Friend {

    @Id
    private Long id;

    // 로그인한 사용자(Session User) 기준 나!
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 상대방 프로필
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "friend_user_id", nullable = false)
    private User friendUser;

    // TODO 친구 상태
    private String status;

    public Friend(Long id, User user, User friendUser) {
        this.id = id;
        this.user = user;
        this.friendUser = friendUser;
    }
}
