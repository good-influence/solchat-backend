package com.sol.solchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class GroupChatRequest {
    @NotBlank(message = "채팅방 제목은 필수입니다.")
    @Size(max = 50, message = "제목은 50자를 넘을 수 없습니다.")
    private String title;

    @NotBlank(message = "초대할 사용자 목록은 비어있을 수 없습니다.")
    private List<Long> userIds;

    private boolean forceCreate;    // 중복 멤버일 경우 새 채팅방 생성 여부
}
