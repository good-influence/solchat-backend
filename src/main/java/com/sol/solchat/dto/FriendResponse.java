package com.sol.solchat.dto;

import com.sol.solchat.domain.User;
import lombok.Getter;

// 클라이언트에게 내려줄 친구 정보 담는 DTO
@Getter
public class FriendResponse {

    private Long id;    // 친구 User ID
    private String username;
    private String nickname;

    public FriendResponse(User friendUser) {
        this.id = friendUser.getId();
        this.username = friendUser.getUsername();
        this.nickname = friendUser.getNickname();
    }
}
