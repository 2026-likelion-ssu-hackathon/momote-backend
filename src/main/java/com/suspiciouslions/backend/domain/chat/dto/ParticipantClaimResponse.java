package com.suspiciouslions.backend.domain.chat.dto;

import com.suspiciouslions.backend.domain.user.entity.Gender;

public record ParticipantClaimResponse(Long userId, String nickname, String profileImageUrl, Gender gender) {
}
