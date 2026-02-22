package com.sol.solchat.repository;

import com.sol.solchat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 특정 채팅방의 메시지를 최신순(내림차순)으로 페이징 조회
    // SELECT Message전체 FROM chat_message WHERE room_id = ? ORDER BY = sent_at DESC LIMIT ? OFFSET ? (Pageable)
    Slice<Message> findByRoomIdOrderBySentAtDesc(Long roomId, Pageable pageable);

    // 특정 방에서, 마지막 읽은 Id보다 큰 메시지 개수 조회
    // SELECT COUNT(*) FROM chat_message WHERE room_id = ? AND id > ?
    int countByRoomIdAndIdGreaterThan(Long roomId, Long lastReadMessageId);

    // SELECT Message전체 From chat_message WHERE room_id = ? ORDER BY id DESC LIMIT 1 (FindTop)
    Optional<Message> findTopByRoomIdOrderByIdDesc(Long roomId);
}
