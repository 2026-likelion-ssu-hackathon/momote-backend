package com.suspiciouslions.backend.domain.chat.dto;

import org.springframework.web.multipart.MultipartFile;

import com.suspiciouslions.backend.domain.user.entity.Gender;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "참가자 닉네임 및 선택 프로필 이미지")
public record ParticipantClaimRequest(
		@Schema(description = "참가자 닉네임", example = "지민", requiredMode = Schema.RequiredMode.REQUIRED)
		String nickname,
		@Schema(description = "선택 프로필 이미지(image/*, 최대 5MB)", type = "string", format = "binary")
		MultipartFile profileImage,
		@Schema(description = "선택 성별", allowableValues = {"MALE", "FEMALE"})
		Gender gender
) {
}
