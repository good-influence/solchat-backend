package com.sol.solchat.repository;

import com.sol.solchat.domain.ChatRoom;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByChatKey(String chatKey);

    /**
     *  사용자가 참여 중인 모든 채팅방 목록을 조회
     *  ChatParticipant (참여자) 테이블과 조인
     *  최신 순 정렬 (스노우플레이크 ID DESC)
     */
    @Query("SELECT cr FROM ChatRoom cr " +
            "JOIN ChatParticipant cp ON cr.id = cp.chatRoomId " +
            "WHERE cp.userId = :userId " +
            "ORDER BY cr.lastMessageId DESC NULLS LAST, cr.id DESC") // NULLS LAST: 대화가 없는 빈 방은 목록 맨 아래로!
    Slice<ChatRoom> findChatRoomsByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     *  특정 멤버 목록(userIds)과 일치하는 구성원을 가진 단체 채팅방 찾기
     *  1. GROUP 타입인 방 중에서,
     *  2. 전체 참여자 수가 memberCount와 같고,
     *  3. 입력된 참여자들(userIds)이 모두 포함된 방 찾기
     */
    @Query("SELECT r FROM ChatRoom r " +
            "JOIN ChatParticipant cp ON r.id = cp.chatRoomId " +
            "WHERE r.type = 'GROUP' " +
            "GROUP BY r.id " +
            "HAVING COUNT(cp) = :memberCount " +
            "AND SUM(CASE WHEN cp.userId IN :userIds THEN 1 ELSE 0 END) = :memberCount")
    List<ChatRoom> findGroupChatByExactMembers(@Param("userIds") List<Long> userIds,
                                               @Param("memberCount") long memberCount);

    @Modifying(clearAutomatically = true) // 영속성 컨텍스트 초기화
    @Query("UPDATE ChatRoom c SET c.lastMessage = :msg, c.lastSentAt = :at, c.lastMessageId = :msgId WHERE c.id = :id")
    void updateLastMessage(@Param(("id")) Long id,
                           @Param("msg") String msg,
                           @Param("at") Long at,
                           @Param("msgId") Long msgId);
}
