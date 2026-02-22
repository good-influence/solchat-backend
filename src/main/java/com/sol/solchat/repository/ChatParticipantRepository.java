package com.sol.solchat.repository;

import com.sol.solchat.domain.ChatParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    /**
     * 두 사용자 ID가 모두 참여하고 있는 채팅방 ID 찾기
     * 1. 두 사용자 ID (userId1, userId2)가 모두 포함된 채팅방 조회
     * 2. HAVING COUNT(cp.userId) = 2 -> '두 명'만 참여하는 채팅방 찾기 (1:1 채팅방)
     */
    @Query(value = "SELECT cp.chatRoomId FROM ChatParticipant cp " +
                    "WHERE cp.userId IN (:userId1, :userId2) " +
                    "GROUP BY cp.chatRoomId " +
                    "HAVING COUNT(cp.userId) = 2")
    List<Long> findExistingDirectChatRoomIds(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     *  특정 사용자가 특정 채팅방의 참여자인지 확인
     *  select count(*) from chat_participant where chat_Room_id = ? and user_id = ? limit 1;
     */
    boolean existsByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    // 특정 채팅방에서 내 참여 정보 조회
    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    // 내(userId)가 참여 중인 방들에 대한 참여 정보 한번에 조회
    // SELECT * FROM chat_participant WHERE user_id = ? AND chat_room_id IN(1, 2, ... 10)
    List<ChatParticipant> findAllByUserIdAndChatRoomIdIn(Long userId, List<Long> chatRoomIds);

    // 최적화 1단계
    /**
     *  조회 없이 바로 업데이트
     * - @Modifying: UPDATE/DELETE 쿼리임을 명시
     * - clearAutomatically = true: 영속성 컨텍스트 초기화 (데이터 불일치 방지)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatParticipant cp SET cp.lastReadMessageId = :msgId " +
            "WHERE cp.chatRoomId = :roomId AND cp.userId = :userId")
    void updateReadStatus(@Param("roomId") Long roomId,
                          @Param("userId") Long userId,
                          @Param("msgId") Long msgId);

    List<ChatParticipant> findAllByChatRoomId(Long roomId);
}
