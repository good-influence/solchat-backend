package com.sol.solchat.service;

import com.sol.solchat.domain.User;
import com.sol.solchat.repository.ChatParticipantRepository;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;


@Disabled("학습용으로 작성한 Mockito 테스트이므로 전체 빌드에서는 제외함")
@ExtendWith(MockitoExtension.class)
public class ChatRoomServiceMockTest {

    @InjectMocks
    private ChatRoomService chatRoomService; // 테스트 대상 (Service A)

    @Mock
    private UserService userService;         // Mocking 대상 (Service B)

    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ChatParticipantRepository chatParticipantRepository;
    @Mock private IdGenerator idGenerator;

    @Test
    @DisplayName("채팅방 생성 시 UserService를 호출해서 유저 확인하기")
    void createChatRoom_UseUserService() {
        // 1. Given
        Long myId = 1L;
        Long targetId = 2L;
        User targetUser = new User(targetId, "target", "pw", "Target");

        given(userService.getUserById(targetId)).willReturn(targetUser);

        given(chatParticipantRepository.findExistingDirectChatRoomIds(myId, targetId))
                .willReturn(Collections.emptyList());
        given(idGenerator.nextId()).willReturn(100L);
        // save 메서드가 호출되면(인자가 뭐든 간에) i = 호출 정보 첫 번째 파라미터를 그대로 리턴해)
        given(chatRoomRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // 2. When
        chatRoomService.createDirectChatRoomIfNotExist(myId, targetId);

        // 3. Then
        verify(userService).getUserById(targetId);
        System.out.println("✅ UserService(Service B)가 올바르게 호출되었습니다.");
    }

}
