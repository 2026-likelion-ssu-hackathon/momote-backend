package com.suspiciouslions.backend.domain.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoomJoinRequest;

public interface ChatRoomJoinRequestRepository extends JpaRepository<ChatRoomJoinRequest, Long> {
}
