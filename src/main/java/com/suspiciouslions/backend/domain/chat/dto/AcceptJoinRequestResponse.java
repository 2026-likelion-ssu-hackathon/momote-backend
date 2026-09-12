package com.suspiciouslions.backend.domain.chat.dto;

import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;

public record AcceptJoinRequestResponse(Long requestId, Long userId, JoinRequestStatus status) {
}
