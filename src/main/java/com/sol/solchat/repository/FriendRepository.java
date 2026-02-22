package com.sol.solchat.repository;

import com.sol.solchat.domain.Friend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FriendRepository extends JpaRepository<Friend, Long> {

    // 특정 유저의 친구 목록 조회
    @Query("SELECT f FROM Friend f JOIN FETCH f.friendUser WHERE f.user.id = :userId")
    List<Friend> findAllByUserId(@Param("userId") Long userId);

    // 쿼리 메서드
    // 친구 관계 존재 여부 확인
    boolean existsByUserIdAndFriendUserId(Long userId, Long friendUserId);
}
