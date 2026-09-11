package com.suspiciouslions.backend.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinChatRoomRequest(@NotBlank String inviteCode) {
}
