package com.suspiciouslions.backend.domain.chat.dto;

public record CreateChatRoomResponse(Long roomId, Long userId, String inviteCode) {
}
