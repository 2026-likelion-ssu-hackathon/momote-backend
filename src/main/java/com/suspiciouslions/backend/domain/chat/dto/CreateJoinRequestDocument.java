package com.suspiciouslions.backend.domain.chat.dto;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "채팅방 입장 요청 multipart 본문")
public record CreateJoinRequestDocument(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "7F3K9X") String inviteCode,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "지민") String nickname,
		@Schema(type = "string", format = "binary", description = "선택 프로필 이미지(image/*, 최대 5MB)")
		MultipartFile profileImage
) {
}
