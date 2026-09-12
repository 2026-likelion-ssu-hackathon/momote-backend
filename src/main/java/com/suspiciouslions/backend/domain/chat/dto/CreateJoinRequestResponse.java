package com.suspiciouslions.backend.domain.chat.dto;

import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "생성된 채팅방 입장 요청")
public record CreateJoinRequestResponse(Long requestId, Long roomId, JoinRequestStatus status) {
}
