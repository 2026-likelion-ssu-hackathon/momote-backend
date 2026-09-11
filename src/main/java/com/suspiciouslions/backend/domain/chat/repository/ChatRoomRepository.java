package com.suspiciouslions.backend.domain.chat.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	@EntityGraph(attributePaths = {"userA", "userB"})
	@Query("select chatRoom from ChatRoom chatRoom where chatRoom.id = :chatRoomId")
	Optional<ChatRoom> findWithUsersById(@Param("chatRoomId") Long chatRoomId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = {"userA", "userB"})
	@Query("select chatRoom from ChatRoom chatRoom where chatRoom.id = :chatRoomId")
	Optional<ChatRoom> findWithUsersByIdForUpdate(@Param("chatRoomId") Long chatRoomId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = {"userA", "userB"})
	Optional<ChatRoom> findWithUsersByInviteCode(String inviteCode);
}
