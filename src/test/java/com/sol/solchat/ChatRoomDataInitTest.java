package com.sol.solchat;

import com.sol.solchat.domain.ChatParticipant;
import com.sol.solchat.domain.ChatRoom;
import com.sol.solchat.repository.ChatParticipantRepository;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.util.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
public class ChatRoomDataInitTest {

    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired private ChatParticipantRepository chatParticipantRepository;
    @Autowired private IdGenerator idGenerator;

    @Test
    @DisplayName("채팅방 50개 강제 생성 (페이징 테스트용)")
//    @Rollback(false) // 테스트 끝나도 DB에 데이터 남기기
    void initChatRooms() {
        Long userId = 1L; // 테스터1
        for (int i = 1; i <= 50; i++) {
            ChatRoom room = new ChatRoom(idGenerator.nextId(),"페이징 테스트방 " + i, "GROUP", UUID.randomUUID().toString());
            room.updateLastMessage("마지막 메시지 " + i, System.currentTimeMillis(), idGenerator.nextId());

            chatRoomRepository.save(room);

            ChatParticipant participant = new ChatParticipant(idGenerator.nextId(), room.getId(), userId);
            chatParticipantRepository.save(participant);
        }
        System.out.println("✅ 채팅방 50개 생성 완료!");
    }
}
