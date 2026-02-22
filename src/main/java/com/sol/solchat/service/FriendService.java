package com.sol.solchat.service;

import com.sol.solchat.domain.Friend;
import com.sol.solchat.domain.User;
import com.sol.solchat.dto.FriendResponse;
import com.sol.solchat.repository.FriendRepository;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendService {

    private final FriendRepository friendRepository;
    private final UserRepository userRepository;
    private final IdGenerator idGenerator;

    // 친구 목록 조회
    public List<FriendResponse> getFriends(Long userId) {
        return friendRepository.findAllByUserId(userId).stream()
                .map(friend -> new FriendResponse(friend.getFriendUser()))
                .collect(Collectors.toList());
    }

    // 친구 추가 (단방향)
    @Transactional
    public void addFriend(Long userId, String friendUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        User friendUser = userRepository.findByUsername(friendUsername)
                .orElseThrow(() -> new IllegalArgumentException("친구를 찾을 수 없습니다."));

        if (user.getId().equals(friendUser.getId())) {
            throw new IllegalArgumentException("자기 자신은 친구로 추가 할 수 없음!!");
        }

        if (friendRepository.existsByUserIdAndFriendUserId(userId, friendUser.getId())) {
            throw new IllegalArgumentException("이미 친구임");
        }

        Friend friend = new Friend(idGenerator.nextId(), user, friendUser);
        friendRepository.save(friend);
    }
}
