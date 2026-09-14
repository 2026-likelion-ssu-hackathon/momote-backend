package com.suspiciouslions.backend.domain.chat.dto;

import java.time.LocalDate;

import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.user.entity.Gender;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "채팅방과 요청 사용자 기준 상대방 정보")
public record ChatRoomResponse(
		@Schema(description = "채팅방 ID", example = "1")
		Long chatRoomId,
		@Schema(description = "채팅방 상태", example = "ACTIVE")
		RoomStatus status,
		@Schema(description = "연애 시작일", example = "2026-08-01", nullable = true)
		LocalDate relationshipStartedOn,
		@Schema(description = "상대방 정보")
		PartnerResponse partner
) {

	@Schema(description = "채팅 상대방 정보")
	public record PartnerResponse(
			@Schema(description = "상대방 사용자 ID", example = "2")
			Long userId,
			@Schema(description = "상대방 닉네임", example = "카카포")
			String nickname,
			@Schema(description = "상대방 프로필 이미지 URL", example = "https://example.com/profile.png",
					nullable = true)
			String profileImageUrl,
			@Schema(description = "상대방 성별", allowableValues = {"MALE", "FEMALE"}, nullable = true)
			Gender gender
	) {
	}
}
