package com.sol.solchat.benchmark;

import com.sol.solchat.domain.ChatParticipant;
import com.sol.solchat.domain.ChatRoom;
import com.sol.solchat.domain.User;
import com.sol.solchat.repository.ChatParticipantRepository;
import com.sol.solchat.repository.ChatRoomRepository;
import com.sol.solchat.repository.UserRepository;
import com.sol.solchat.util.IdGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
@ActiveProfiles("test") // 테스트 프로필 사용 시
public class SyncPerformanceTest {

    @Autowired
    private ChatParticipantRepository chatParticipantRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private IdGenerator idGenerator;

    private Long chatRoomId;
    private List<Long> userIds = new ArrayList<>();

    // 테스트할 데이터 규모
    private static final int USER_COUNT = 1000;

    @BeforeEach
    void setUp() {
        ChatRoom room = new ChatRoom(idGenerator.nextId(),"Benchmark Room", "GROUP", java.util.UUID.randomUUID().toString());
        chatRoomRepository.save(room);

        chatRoomId = room.getId();

        List<User> users = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            users.add(new User(idGenerator.nextId(), "user" + i, "pass", "nick" + i));
        }
        userRepository.saveAll(users);

        List<ChatParticipant> participants = new ArrayList<>();
        for (User user : users) {
            userIds.add(user.getId());
            participants.add(new ChatParticipant(idGenerator.nextId(), chatRoomId, user.getId()));
        }
        chatParticipantRepository.saveAll(participants);

        // 영속성 컨텍스트 비우기 (쿼리 캐시 방지)
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("[Worst] 1단계: N+1방식 (조회 후 업데이트 반복)")
    @Transactional
    void testOriginalMethod() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // ---- 로직 시작 ----
        for (Long userId : userIds) {
            // 조회 (SELECT)
            chatParticipantRepository.findByChatRoomIdAndUserId(chatRoomId, userId)
                    .ifPresent(p -> {
                        // 수정
                        p.updateLastReadMessageId(999L);
                    });
        }

        entityManager.flush();  // 실제 DB 반영 (쿼리 폭발 부분)
        // ---- 로직 종료 ----

        stopWatch.stop();
        System.out.println("==========================================");
        System.out.println("[Worst] N+1 방식 소요 시간: " + stopWatch.getTotalTimeMillis() + "ms");
        System.out.println("예상 쿼리 수: " + (USER_COUNT * 2) + "개 (SELECT + UPDATE)");
        System.out.println("==========================================");
        //  [Worst] N+1 방식 소요 시간: 1681ms
        //  예상 쿼리 수: 2000개 (SELECT + UPDATE)
    }

    @Test
    @DisplayName("[Lv.1] 직접 Update 쿼리 (조회 없이 바로 수정)")
    @Transactional
    void testLevel1Method() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // ---- 로직 시작 ----
        for (Long userId : userIds) {
            // SELECT 없이 바로 UPDATE (JPQL 호출) Modifying 쿼리루
            chatParticipantRepository.updateReadStatus(chatRoomId, userId, 999L);
        }
        // updateReadStatus는 @Modifying이라 호출 즉시 쿼리 나감 (flush 불필요하지만 비교 위해 작성)
        entityManager.flush();
        // ---- 로직 종료 ----

        stopWatch.stop();
        System.out.println("==========================================");
        System.out.println("[Lv.1] 직접 Update 방식 소요 시간: " + stopWatch.getTotalTimeMillis() + "ms");
        System.out.println("예상 쿼리 수: " + USER_COUNT + "개 (UPDATE only)");
        System.out.println("==========================================");
//        [Lv.1] 직접 Update 방식 소요 시간: 668ms
//        예상 쿼리 수: 1000개 (UPDATE only)
    }

    @Test
    @DisplayName("[Lv.2] Bulk Fetch + Batch Update")
    @Transactional
    void testLevel2Method() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // ---- 로직 시작 ----
        // 한 번에 조회 (SELECT 1회)
        List<ChatParticipant> participants = chatParticipantRepository.findAllByChatRoomId(chatRoomId);

        // 메모리에서 루프 (DB 접근 하지 않음)
        for (ChatParticipant p : participants) {
            p.updateLastReadMessageId(999L);
        }

        // 한 번에 반영 (Batch Update)
        entityManager.flush();
        // ---- 로직 종료 ----

        stopWatch.stop();
        System.out.println("==========================================");
        System.out.println("[Lv.2] Batch Update 방식 소요 시간: " + stopWatch.getTotalTimeMillis() + "ms");
        System.out.println("예상 쿼리 수: 1 (SELECT) + 1 (Batch UPDATE) = 총 2개");
        System.out.println("==========================================");

//        [Lv.2] Batch Update 방식 소요 시간: 233ms
//        예상 쿼리 수: 1 (SELECT) + 1 (Batch UPDATE) = 총 2개
    }
}
