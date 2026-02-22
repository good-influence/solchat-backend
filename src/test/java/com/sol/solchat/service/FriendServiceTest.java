package com.sol.solchat.service;

import com.sol.solchat.domain.User;
import com.sol.solchat.repository.FriendRepository;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class FriendServiceTest {

    @InjectMocks
    private FriendService friendService;

    @Mock private FriendRepository friendRepository;
    @Mock private UserRepository userRepository;
    @Mock private IdGenerator idGenerator;

    @Test
    @DisplayName("친구 추가 성공: 정상적인 경우 친구 관계가 저장되어야 한다")
    void addFriendSuccess() {
        // 1. Given (상황 설정)
        Long myId = 1L;
        String friendName = "friend_user";
        Long friendId = 2L;

        User me = new User(myId, "my_id", "pw", "Me");
        User friend = new User(friendId, friendName, "pw", "Friend");

        given(userRepository.findById(myId)).willReturn(Optional.of(me));
        given(userRepository.findByUsername(friendName)).willReturn(Optional.of(friend));
        given(friendRepository.existsByUserIdAndFriendUserId(myId, friendId)).willReturn(false);
        given(idGenerator.nextId()).willReturn(100L);

        // 2. When (실행)
        friendService.addFriend(myId, friendName);

        // 3. Then (검증)
        // friendRepository.save() 가 호출되었는지 확인, 저장되는 Friend 객체의 주인이 '나'이고, 대상이 '친구'인지 확인
        verify(friendRepository).save(argThat(f ->
                f.getUser().getId().equals(myId) &&
                f.getFriendUser().getId().equals(friendId)));
    }

    @Test
    @DisplayName("친구 추가 실패: 자기 자신을 추가하려고 하면 예외가 발생해야 한다")
    void addFriendFail_Self() {
        // 1. Given
        Long myId = 1L;
        String myName = "my_id";
        User me = new User(myId, myName, "pw", "Me");

        given(userRepository.findById(myId)).willReturn(Optional.of(me));
        given(userRepository.findByUsername(myName)).willReturn(Optional.of(me));

        // 2. When & 3. Then
        assertThatThrownBy(() -> friendService.addFriend(myId, myName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신");
    }

    @Test
    @DisplayName("친구 추가 실패2: 이미 친구 관계라면 예외가 발생해야 한다")
    void addFriendFail_AlreadyFriend() {
        // 1. Given
        Long myId = 1L;
        String friendName = "old_friend";
        Long friendId = 2L;

        User me = new User(myId, "my_id", "pw", "Me");
        User friend = new User(friendId, friendName, "pw", "Friend");

        given(userRepository.findById(myId)).willReturn(Optional.of(me));
        given(userRepository.findByUsername(friendName)).willReturn(Optional.of(friend));

        given(friendRepository.existsByUserIdAndFriendUserId(myId, friendId)).willReturn(true);

        // 2. When & 3. Then
        assertThatThrownBy(() -> friendService.addFriend(myId, friendName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 친구");
    }
}
