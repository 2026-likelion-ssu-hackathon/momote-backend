package com.suspiciouslions.backend.domain.ai.service;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.suspiciouslions.backend.domain.ai.dto.AiResultResponse;
import com.suspiciouslions.backend.domain.ai.entity.AiResult;
import com.suspiciouslions.backend.domain.ai.repository.AiResultRepository;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;

@Service
public class AiResultQueryService {

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final AiResultRepository aiResultRepository;

	public AiResultQueryService(UserRepository userRepository, ChatRoomRepository chatRoomRepository,
			AiResultRepository aiResultRepository) {
		this.userRepository = userRepository;
		this.chatRoomRepository = chatRoomRepository;
		this.aiResultRepository = aiResultRepository;
	}

	@Transactional(readOnly = true)
	public List<AiResultResponse> getResults(Long chatRoomId, Long userId, Long afterResultId,
			Long triggerMessageId) {
		if (!userRepository.existsById(userId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}

		ChatRoom chatRoom = chatRoomRepository.findWithUsersById(chatRoomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
		if (!Objects.equals(chatRoom.getUserA().getId(), userId)
				&& (chatRoom.getUserB() == null || !Objects.equals(chatRoom.getUserB().getId(), userId))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a chat room participant");
		}

		return aiResultRepository.findVisibleResults(chatRoomId, userId, afterResultId, triggerMessageId)
				.stream()
				.map(this::toResponse)
				.toList();
	}

	private AiResultResponse toResponse(AiResult result) {
		return new AiResultResponse(
				result.getId(),
				result.getChatRoom().getId(),
				result.getResultType(),
				result.getVisibilityType(),
				result.getContentType(),
				result.getTriggerMessageIds(),
				result.getResultData(),
				result.getCreatedAt()
		);
	}
}
