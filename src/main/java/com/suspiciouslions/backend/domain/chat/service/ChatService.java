package com.suspiciouslions.backend.domain.chat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.security.SecureRandom;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import com.suspiciouslions.backend.domain.ai.event.MessageCreatedEvent;
import com.suspiciouslions.backend.domain.chat.dto.ChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.ChatRoomResponse.PartnerResponse;
import com.suspiciouslions.backend.domain.chat.dto.MessageResponse;
import com.suspiciouslions.backend.domain.chat.dto.SendMessageRequest;
import com.suspiciouslions.backend.domain.chat.dto.CreateChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.JoinChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.ParticipantClaimResponse;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.Message;
import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.chat.repository.MessageRepository;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.entity.Gender;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage.UploadedProfileImage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorageException;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageValidation;

@Service
public class ChatService {
	private static final char[] INVITE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final ChatRoomRepository chatRoomRepository;
	private final MessageRepository messageRepository;
	private final TransactionTemplate transactionTemplate;
	private final ApplicationEventPublisher eventPublisher;
	private final ProfileImageStorage profileImageStorage;

	public ChatService(UserRepository userRepository, ChatRoomRepository chatRoomRepository,
			MessageRepository messageRepository, PlatformTransactionManager transactionManager,
			ApplicationEventPublisher eventPublisher, ProfileImageStorage profileImageStorage) {
		this.userRepository = userRepository;
		this.chatRoomRepository = chatRoomRepository;
		this.messageRepository = messageRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.eventPublisher = eventPublisher;
		this.profileImageStorage = profileImageStorage;
	}

	public CreateChatRoomResponse createChatRoom() {
		for (int attempt = 0; attempt < 10; attempt++) {
			try {
				CreateChatRoomResponse response = transactionTemplate.execute(status -> {
					OffsetDateTime now = OffsetDateTime.now();
					User user = userRepository.save(new User(null, null, null, null, now, now));
					ChatRoom room = new ChatRoom(user, null, null, RoomStatus.ACTIVE, now, null);
					room.assignInviteCode(createInviteCode());
					ChatRoom saved = chatRoomRepository.saveAndFlush(room);
					return new CreateChatRoomResponse(saved.getId(), user.getId(), saved.getInviteCode());
				});
				return Objects.requireNonNull(response);
			} catch (DataIntegrityViolationException exception) {
				if (attempt == 9) throw exception;
			}
		}
		throw new IllegalStateException("Unable to create invite code");
	}

	@Transactional
	public JoinChatRoomResponse joinChatRoom(String inviteCode) {
		ChatRoom room = chatRoomRepository.findWithUsersByInviteCode(inviteCode)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite code not found"));
		if (room.getUserB() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chat room is full");
		}
		User user = userRepository.save(new User(null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now()));
		room.assignUserB(user);
		return new JoinChatRoomResponse(room.getId(), user.getId());
	}

	public ParticipantClaimResponse claimNickname(Long chatRoomId, Long userId, String nickname,
			MultipartFile profileImage, Gender gender) {
		ProfileImageValidation.validate(profileImage);
		AtomicReference<UploadedProfileImage> uploaded = new AtomicReference<>();
		try {
			ParticipantClaimResponse response = transactionTemplate.execute(status -> {
				User user = findUser(userId);
				ChatRoom room = chatRoomRepository.findWithUsersByIdForUpdate(chatRoomId)
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
				validateClaimableParticipant(room, userId);
				UploadedProfileImage image = profileImage == null ? null : profileImageStorage.upload(profileImage);
				uploaded.set(image);
				String profileImageUrl = image == null ? null : image.secureUrl();
				user.claimProfile(nickname, profileImageUrl, gender, OffsetDateTime.now());
				return new ParticipantClaimResponse(
						user.getId(), user.getNickname(), user.getProfileImageUrl(), user.getGender());
			});
			return Objects.requireNonNull(response);
		} catch (ProfileImageStorageException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Profile image upload failed");
		} catch (RuntimeException exception) {
			compensateUploadedImage(uploaded.get());
			throw exception;
		}
	}

	public ParticipantClaimResponse claimNickname(Long chatRoomId, Long userId, String nickname,
			MultipartFile profileImage) {
		return claimNickname(chatRoomId, userId, nickname, profileImage, null);
	}

	private void compensateUploadedImage(UploadedProfileImage uploaded) {
		if (uploaded == null) return;
		try {
			profileImageStorage.delete(uploaded.publicId());
		} catch (RuntimeException ignored) {
			// The original database/commit exception is the primary failure.
		}
	}

	@Transactional(readOnly = true)
	public ChatRoomResponse getChatRoom(Long chatRoomId, Long userId) {
		ChatRoom chatRoom = findChatRoomForUser(chatRoomId, userId);
		User partner = Objects.equals(chatRoom.getUserA().getId(), userId)
				? chatRoom.getUserB()
				: chatRoom.getUserA();

		PartnerResponse partnerResponse = partner == null ? null
				: new PartnerResponse(
						partner.getId(), partner.getNickname(), partner.getProfileImageUrl(), partner.getGender());
		return new ChatRoomResponse(
				chatRoom.getId(),
				chatRoom.getRoomStatus(),
				chatRoom.getRelationshipStartedOn(),
				partnerResponse
		);
	}

	public MessageResponse sendMessage(Long chatRoomId, Long userId, SendMessageRequest request) {
		User sender = findUser(userId);
		ChatRoom chatRoom = findChatRoom(chatRoomId);
		validateParticipant(chatRoom, userId);
		if (chatRoom.getUserB() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chat room is waiting for a second participant");
		}

		return messageRepository.findBySenderIdAndClientMessageId(userId, request.clientMessageId())
				.map(existing -> existingMessageForRoom(existing, chatRoomId))
				.orElseGet(() -> saveMessageIdempotently(chatRoom, sender, request));
	}

	@Transactional(readOnly = true)
	public List<MessageResponse> getMessages(Long chatRoomId, Long userId, Long beforeMessageId,
			Long afterMessageId, int size) {
		findChatRoomForUser(chatRoomId, userId);
		PageRequest limit = PageRequest.of(0, size);

		if (beforeMessageId != null) {
			List<Message> messages = new ArrayList<>(messageRepository
					.findByChatRoomIdAndIdLessThanOrderByIdDesc(chatRoomId, beforeMessageId, limit));
			Collections.reverse(messages);
			return messages.stream().map(this::toResponse).toList();
		}

		if (afterMessageId != null) {
			return messageRepository
					.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(chatRoomId, afterMessageId, limit)
					.stream()
					.map(this::toResponse)
					.toList();
		}

		List<Message> messages = new ArrayList<>(
				messageRepository.findByChatRoomIdOrderByIdDesc(chatRoomId, limit));
		Collections.reverse(messages);
		return messages.stream().map(this::toResponse).toList();
	}

	private MessageResponse saveMessageIdempotently(ChatRoom chatRoom, User sender, SendMessageRequest request) {
		try {
			Message saved = transactionTemplate.execute(status -> {
				Message newMessage = messageRepository.saveAndFlush(new Message(
						chatRoom,
						sender,
						request.clientMessageId(),
						request.content(),
						request.sentAt()
				));
				eventPublisher.publishEvent(new MessageCreatedEvent(newMessage.getId(), chatRoom.getId()));
				return newMessage;
			});
			return toResponse(Objects.requireNonNull(saved));
		} catch (DataIntegrityViolationException exception) {
			return messageRepository.findBySenderIdAndClientMessageId(sender.getId(), request.clientMessageId())
					.map(existing -> existingMessageForRoom(existing, chatRoom.getId()))
					.orElseThrow(() -> exception);
		}
	}

	private MessageResponse existingMessageForRoom(Message message, Long requestedChatRoomId) {
		if (!Objects.equals(message.getChatRoom().getId(), requestedChatRoomId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"clientMessageId is already used in another chat room");
		}
		return toResponse(message);
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
	}

	private ChatRoom findChatRoom(Long chatRoomId) {
		return chatRoomRepository.findWithUsersById(chatRoomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
	}

	private ChatRoom findChatRoomForUser(Long chatRoomId, Long userId) {
		findUser(userId);
		ChatRoom chatRoom = findChatRoom(chatRoomId);
		validateParticipant(chatRoom, userId);
		return chatRoom;
	}

	private void validateParticipant(ChatRoom chatRoom, Long userId) {
		if (!Objects.equals(chatRoom.getUserA().getId(), userId)
				&& (chatRoom.getUserB() == null || !Objects.equals(chatRoom.getUserB().getId(), userId))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not a chat room participant");
		}
	}

	private void validateClaimableParticipant(ChatRoom room, Long userId) {
		validateParticipant(room, userId);
		User user = Objects.equals(room.getUserA().getId(), userId) ? room.getUserA() : room.getUserB();
		if (user.getNickname() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Nickname is already registered");
		}
		if (room.getUserA().getNickname() != null && room.getUserB() != null && room.getUserB().getNickname() != null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Both participants already registered nicknames");
		}
	}

	private String createInviteCode() {
		char[] value = new char[6];
		for (int index = 0; index < value.length; index++) value[index] = INVITE_ALPHABET[RANDOM.nextInt(INVITE_ALPHABET.length)];
		return new String(value);
	}

	private MessageResponse toResponse(Message message) {
		return new MessageResponse(
				message.getId(),
				message.getChatRoom().getId(),
				message.getSender().getId(),
				message.getClientMessageId(),
				message.getContent(),
				message.getSentAt()
		);
	}
}
