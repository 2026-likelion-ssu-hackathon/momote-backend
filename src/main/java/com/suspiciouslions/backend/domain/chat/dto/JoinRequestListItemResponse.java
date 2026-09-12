package com.suspiciouslions.backend.domain.chat.dto;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "방장이 조회하는 입장 요청 항목")
public record JoinRequestListItemResponse(
		Long requestId,
		String nickname,
		@Schema(nullable = true) String profileImageUrl,
		OffsetDateTime requestedAt
) {
}
