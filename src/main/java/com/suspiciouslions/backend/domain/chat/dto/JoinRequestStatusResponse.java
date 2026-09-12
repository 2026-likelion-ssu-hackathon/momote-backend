package com.suspiciouslions.backend.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "채팅방 입장 요청 처리 상태")
public record JoinRequestStatusResponse(
		Long requestId,
		JoinRequestStatus status,
		Long roomId,
		Long userId,
		String nickname,
		String profileImageUrl
) {
}
