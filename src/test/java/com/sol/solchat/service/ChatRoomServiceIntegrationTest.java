package com.sol.solchat.service;

import com.sol.solchat.domain.ChatRoom;
import com.sol.solchat.domain.ChatRoomCreationResult;
import com.sol.solchat.domain.User;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) // 1. 진짜 스프링을 띄운다
@Transactional // 2. 테스트 시작할 때 트랜잭션 열고, 끝날 때 (성공/실패) 무조건 롤백
public class ChatRoomServiceIntegrationTest {

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Test
    @DisplayName("진짜 DB 테스트: 유저 두 명 만들고 1:1 채팅방 생성 및 DB 저장 테스트")
    void createDirectChatRoom_Integration() {
        // 1. Given (진짜 데이터 DB 넣기)
        Long user1Id = idGenerator.nextId();
        User user1 = new User(user1Id, "userA", "pw", "UserA");
        userRepository.save(user1);

        Long user2Id = idGenerator.nextId();
        User user2 = new User(user2Id, "userB", "pw", "UserB");
        userRepository.save(user2);

        // 2. When (서비스 호출)
        // 실제 서비스의 메서드 안에서 JPA 동작 및 실제 DB 조회 수행
        ChatRoomCreationResult result = chatRoomService.createDirectChatRoomIfNotExist(user1Id, user2Id);

        // 3. Then (검증)
        // 결과가 새로 생성된 방인지 확인
        assertThat(result.newlyCreated()).isTrue();

        // 실제 DB 확인
        ChatRoom savedRoom = chatRoomRepository.findById(result.chatRoom().getId()).orElse(null);

        assertThat(savedRoom).isNotNull();
        assertThat(savedRoom.getChatKey()).contains(user1Id.toString());    // 키 생성 로직 확인
        assertThat(savedRoom.getType()).isEqualTo("DIRECT");

        System.out.println("✅ 실제 DB에 저장된 방 ID: " + savedRoom.getId());
    }
}
