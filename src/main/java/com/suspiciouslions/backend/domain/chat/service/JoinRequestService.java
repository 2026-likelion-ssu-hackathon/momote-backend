package com.suspiciouslions.backend.domain.chat.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.suspiciouslions.backend.domain.chat.dto.CreateJoinRequestResponse;
import com.suspiciouslions.backend.domain.chat.dto.JoinRequestStatusResponse;
import com.suspiciouslions.backend.domain.chat.dto.JoinRequestListItemResponse;
import com.suspiciouslions.backend.domain.chat.dto.AcceptJoinRequestResponse;
import com.suspiciouslions.backend.domain.chat.dto.RejectJoinRequestResponse;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoomJoinRequest;
import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomJoinRequestRepository;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.entity.Gender;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage.UploadedProfileImage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorageException;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageValidation;

@Service
public class JoinRequestService {
	private static final Logger log = LoggerFactory.getLogger(JoinRequestService.class);

	private final ChatRoomRepository chatRoomRepository;
	private final ChatRoomJoinRequestRepository joinRequestRepository;
	private final UserRepository userRepository;
	private final ProfileImageStorage profileImageStorage;
	private final TransactionTemplate transactionTemplate;

	public JoinRequestService(ChatRoomRepository chatRoomRepository,
			ChatRoomJoinRequestRepository joinRequestRepository,
			UserRepository userRepository,
			ProfileImageStorage profileImageStorage,
			PlatformTransactionManager transactionManager) {
		this.chatRoomRepository = chatRoomRepository;
		this.joinRequestRepository = joinRequestRepository;
		this.userRepository = userRepository;
		this.profileImageStorage = profileImageStorage;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Transactional(readOnly = true)
	public List<JoinRequestListItemResponse> getRequests(Long roomId, Long ownerUserId, JoinRequestStatus status) {
		ChatRoom room = chatRoomRepository.findWithUsersById(roomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
		validateOwner(room, ownerUserId);
		return joinRequestRepository.findByChatRoomIdAndStatusOrderByRequestedAtAscIdAsc(roomId, status)
				.stream()
				.map(request -> new JoinRequestListItemResponse(
						request.getId(), request.getNickname(), request.getProfileImageUrl(),
						request.getGender(), request.getRequestedAt()))
				.toList();
	}

	public AcceptJoinRequestResponse accept(Long roomId, Long requestId, Long ownerUserId) {
		AcceptOutcome outcome = Objects.requireNonNull(transactionTemplate.execute(status -> {
			ChatRoom room = findRoomForProcessing(roomId, ownerUserId);
			ChatRoomJoinRequest request = findRequestForProcessing(roomId, requestId);
			validatePending(request);
			if (room.getUserB() != null) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Chat room is full");
			}

			OffsetDateTime now = OffsetDateTime.now();
			User user = userRepository.save(new User(
					null, null, request.getNickname(), request.getProfileImageUrl(), request.getGender(), now, now));
			room.assignUserB(user);
			request.accept(user);

			List<ChatRoomJoinRequest> remaining = joinRequestRepository
					.findByChatRoomIdAndStatusForUpdate(roomId, JoinRequestStatus.PENDING);
			List<String> unusedImageIds = remaining.stream()
					.filter(other -> !Objects.equals(other.getId(), requestId))
					.peek(ChatRoomJoinRequest::reject)
					.map(ChatRoomJoinRequest::getProfileImagePublicId)
					.filter(Objects::nonNull)
					.toList();
			return new AcceptOutcome(
					new AcceptJoinRequestResponse(request.getId(), user.getId(), request.getStatus()), unusedImageIds);
		}));
		deleteImages(outcome.unusedImageIds());
		return outcome.response();
	}

	public RejectJoinRequestResponse reject(Long roomId, Long requestId, Long ownerUserId) {
		RejectOutcome outcome = Objects.requireNonNull(transactionTemplate.execute(status -> {
			findRoomForProcessing(roomId, ownerUserId);
			ChatRoomJoinRequest request = findRequestForProcessing(roomId, requestId);
			validatePending(request);
			request.reject();
			return new RejectOutcome(
					new RejectJoinRequestResponse(request.getId(), request.getStatus()), request.getProfileImagePublicId());
		}));
		deleteImage(outcome.unusedImageId());
		return outcome.response();
	}

	public CreateJoinRequestResponse create(String inviteCode, String nickname,
			MultipartFile profileImage, Gender gender) {
		ProfileImageValidation.validate(profileImage);
		AtomicReference<UploadedProfileImage> uploaded = new AtomicReference<>();
		try {
			CreateJoinRequestResponse response = transactionTemplate.execute(status -> {
				ChatRoom room = chatRoomRepository.findWithUsersByInviteCode(inviteCode)
						.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite code not found"));
				if (room.getUserB() != null) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Chat room is full");
				}
				UploadedProfileImage image = profileImage == null ? null : profileImageStorage.upload(profileImage);
				uploaded.set(image);
				ChatRoomJoinRequest request = joinRequestRepository.saveAndFlush(new ChatRoomJoinRequest(
						room,
						nickname,
						image == null ? null : image.secureUrl(),
						image == null ? null : image.publicId(),
						gender,
						OffsetDateTime.now()
				));
				return new CreateJoinRequestResponse(request.getId(), room.getId(), request.getStatus());
			});
			return Objects.requireNonNull(response);
		} catch (ProfileImageStorageException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Profile image upload failed");
		} catch (RuntimeException exception) {
			compensate(uploaded.get());
			throw exception;
		}
	}

	public CreateJoinRequestResponse create(String inviteCode, String nickname, MultipartFile profileImage) {
		return create(inviteCode, nickname, profileImage, null);
	}

	@Transactional(readOnly = true)
	public JoinRequestStatusResponse getStatus(Long requestId) {
		ChatRoomJoinRequest request = joinRequestRepository.findById(requestId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found"));
		if (request.getStatus() != JoinRequestStatus.ACCEPTED) {
			return new JoinRequestStatusResponse(request.getId(), request.getStatus(), null, null, null, null, null);
		}
		User user = request.getAssignedUser();
		return new JoinRequestStatusResponse(
				request.getId(),
				request.getStatus(),
				request.getChatRoom().getId(),
				user == null ? null : user.getId(),
				request.getNickname(),
				request.getProfileImageUrl(),
				user == null ? request.getGender() : user.getGender()
		);
	}

	private void compensate(UploadedProfileImage uploaded) {
		if (uploaded == null) return;
		try {
			profileImageStorage.delete(uploaded.publicId());
		} catch (RuntimeException ignored) {
			// Preserve the original database/commit exception.
		}
	}

	private ChatRoom findRoomForProcessing(Long roomId, Long ownerUserId) {
		ChatRoom room = chatRoomRepository.findWithUsersByIdForUpdate(roomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat room not found"));
		validateOwner(room, ownerUserId);
		return room;
	}

	private ChatRoomJoinRequest findRequestForProcessing(Long roomId, Long requestId) {
		return joinRequestRepository.findByIdAndChatRoomIdForUpdate(requestId, roomId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found"));
	}

	private void validateOwner(ChatRoom room, Long ownerUserId) {
		if (!Objects.equals(room.getUserA().getId(), ownerUserId)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the chat room owner can process join requests");
		}
	}

	private void validatePending(ChatRoomJoinRequest request) {
		if (request.getStatus() != JoinRequestStatus.PENDING) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Join request is already processed");
		}
	}

	private void deleteImages(List<String> publicIds) {
		publicIds.forEach(this::deleteImage);
	}

	private void deleteImage(String publicId) {
		if (publicId == null) return;
		try {
			profileImageStorage.delete(publicId);
		} catch (RuntimeException exception) {
			log.warn("Failed to delete unused profile image. publicId={}", publicId);
		}
	}

	private record AcceptOutcome(AcceptJoinRequestResponse response, List<String> unusedImageIds) {
	}

	private record RejectOutcome(RejectJoinRequestResponse response, String unusedImageId) {
	}
}
