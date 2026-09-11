package com.suspiciouslions.backend.domain.emotion.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.emotion.dto.EmotionAnalysisResponse;
import com.suspiciouslions.backend.domain.emotion.entity.EmotionAnalysis;
import com.suspiciouslions.backend.domain.emotion.repository.EmotionAnalysisRepository;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;

@Service
public class EmotionAnalysisQueryService {

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final EmotionAnalysisRepository emotionAnalysisRepository;
	private final Clock clock;

	public EmotionAnalysisQueryService(UserRepository userRepository, ChatRoomRepository chatRoomRepository,
			EmotionAnalysisRepository emotionAnalysisRepository, Clock clock) {
		this.userRepository = userRepository;
		this.chatRoomRepository = chatRoomRepository;
		this.emotionAnalysisRepository = emotionAnalysisRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<EmotionAnalysisResponse> getCurrentStates(Long chatRoomId, Long userId) {
		if (!userRepository.existsById(userId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
		}

		ChatRoom chatRoom = chatRoomRepository.findWithUsersById(chatRoomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
		if (!Objects.equals(chatRoom.getUserA().getId(), userId)
				&& (chatRoom.getUserB() == null || !Objects.equals(chatRoom.getUserB().getId(), userId))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a chat room participant");
		}

		OffsetDateTime now = OffsetDateTime.now(clock);
		return emotionAnalysisRepository.findCurrentVisibleStates(chatRoomId, userId, now)
				.stream()
				.map(this::toResponse)
				.toList();
	}

	private EmotionAnalysisResponse toResponse(EmotionAnalysis emotion) {
		return new EmotionAnalysisResponse(
				emotion.getId(),
				emotion.getChatRoom().getId(),
				emotion.getSubjectUser().getId(),
				emotion.getEmotionType(),
				emotion.getIntensityValue(),
				emotion.getStateText(),
				emotion.getDetectedAt(),
				emotion.getExpiresAt()
		);
	}
}
