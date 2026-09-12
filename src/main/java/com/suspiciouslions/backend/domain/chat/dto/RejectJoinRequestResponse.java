package com.suspiciouslions.backend.domain.chat.dto;

import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;

public record RejectJoinRequestResponse(Long requestId, JoinRequestStatus status) {
}
