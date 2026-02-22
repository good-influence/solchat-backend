package com.sol.solchat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FriendRequest {

    @NotBlank(message = "친구 아이디는 필수입니다.")
    private String username;
}
