package com.suspiciouslions.backend.domain.ai.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.suspiciouslions.backend.domain.ai.dto.AiAnalysisRequest;
import com.suspiciouslions.backend.domain.ai.dto.AiAnalysisRequest.AnalysisMessage;
import com.suspiciouslions.backend.domain.ai.dto.AiAnalysisRequest.Participant;
import com.suspiciouslions.backend.domain.ai.dto.AiAnalysisRequest.ParticipantKey;
import com.suspiciouslions.backend.domain.ai.dto.AiAnalysisRequest.RecentResult;
import com.suspiciouslions.backend.domain.ai.entity.AiResult;
import com.suspiciouslions.backend.domain.ai.entity.AiResultType;
import com.suspiciouslions.backend.domain.ai.repository.AiResultRepository;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.Message;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.chat.repository.MessageRepository;

@Service
public class AiAnalysisRequestService {

	private static final int MESSAGE_LIMIT = 30;
	private static final int RECENT_RESULT_LIMIT = 20;
	private static final List<AiResultType> RECOMMENDATION_TYPES = List.of(
			AiResultType.YOUTUBE_RECOMMENDATION,
			AiResultType.DATE_RECOMMENDATION
	);

	private final ChatRoomRepository chatRoomRepository;
	private final MessageRepository messageRepository;
	private final AiResultRepository aiResultRepository;
	private final Clock clock;

	public AiAnalysisRequestService(ChatRoomRepository chatRoomRepository, MessageRepository messageRepository,
			AiResultRepository aiResultRepository, Clock clock) {
		this.chatRoomRepository = chatRoomRepository;
		this.messageRepository = messageRepository;
		this.aiResultRepository = aiResultRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AiAnalysisRequest create(Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findWithUsersById(chatRoomId)
				.orElseThrow(() -> new IllegalArgumentException("Chat room not found: " + chatRoomId));
		if (chatRoom.getUserB() == null) {
			throw new IllegalStateException("Chat room is waiting for a second participant: " + chatRoomId);
		}

		List<Message> recentMessages = new ArrayList<>(messageRepository
				.findByChatRoomIdOrderBySentAtDescIdDesc(chatRoomId, PageRequest.of(0, MESSAGE_LIMIT)));
		Collections.reverse(recentMessages);

		List<AnalysisMessage> messages = recentMessages.stream()
				.map(message -> new AnalysisMessage(
						message.getId(),
						participantKey(chatRoom, message.getSender().getId()),
						message.getContent(),
						message.getSentAt()
				))
				.toList();

		OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(30);
		List<RecentResult> recentResults = aiResultRepository
				.findByChatRoomIdAndResultTypeInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
						chatRoomId, RECOMMENDATION_TYPES, cutoff, PageRequest.of(0, RECENT_RESULT_LIMIT))
				.stream()
				.map(this::toRecentResult)
				.toList();

		return new AiAnalysisRequest(
				UUID.randomUUID(),
				chatRoomId,
				List.of(new Participant(ParticipantKey.USER_A), new Participant(ParticipantKey.USER_B)),
				messages,
				recentResults
		);
	}

	private ParticipantKey participantKey(ChatRoom chatRoom, Long userId) {
		if (chatRoom.getUserA().getId().equals(userId)) {
			return ParticipantKey.USER_A;
		}
		if (chatRoom.getUserB().getId().equals(userId)) {
			return ParticipantKey.USER_B;
		}
		throw new IllegalArgumentException("Message sender is not a chat room participant: " + userId);
	}

	private RecentResult toRecentResult(AiResult result) {
		return new RecentResult(result.getResultType(), referenceKey(result), result.getCreatedAt());
	}

	private String referenceKey(AiResult result) {
		Map<String, Object> resultData = result.getResultData();
		if (result.getResultType() == AiResultType.YOUTUBE_RECOMMENDATION) {
			return requiredString(resultData.get("videoId"), "resultData.videoId");
		}
		if (result.getResultType() == AiResultType.DATE_RECOMMENDATION) {
			Object mainPlace = resultData.get("mainPlace");
			if (mainPlace instanceof Map<?, ?> mainPlaceMap) {
				return requiredString(mainPlaceMap.get("name"), "resultData.mainPlace.name");
			}
			throw new IllegalArgumentException("Missing resultData.mainPlace");
		}
		throw new IllegalArgumentException("Unsupported recent result type: " + result.getResultType());
	}

	private String requiredString(Object value, String fieldName) {
		if (value instanceof String string && !string.isBlank()) {
			return string;
		}
		throw new IllegalArgumentException("Missing " + fieldName);
	}
}
