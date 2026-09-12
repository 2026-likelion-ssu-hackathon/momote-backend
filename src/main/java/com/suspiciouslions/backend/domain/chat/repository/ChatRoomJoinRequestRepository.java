package com.suspiciouslions.backend.domain.chat.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoomJoinRequest;
import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;

public interface ChatRoomJoinRequestRepository extends JpaRepository<ChatRoomJoinRequest, Long> {

	List<ChatRoomJoinRequest> findByChatRoomIdAndStatusOrderByRequestedAtAscIdAsc(
			Long chatRoomId, JoinRequestStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = {"chatRoom", "assignedUser"})
	@Query("select request from ChatRoomJoinRequest request "
			+ "where request.id = :requestId and request.chatRoom.id = :chatRoomId")
	Optional<ChatRoomJoinRequest> findByIdAndChatRoomIdForUpdate(
			@Param("requestId") Long requestId, @Param("chatRoomId") Long chatRoomId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from ChatRoomJoinRequest request "
			+ "where request.chatRoom.id = :chatRoomId and request.status = :status "
			+ "order by request.requestedAt asc, request.id asc")
	List<ChatRoomJoinRequest> findByChatRoomIdAndStatusForUpdate(
			@Param("chatRoomId") Long chatRoomId, @Param("status") JoinRequestStatus status);
}
