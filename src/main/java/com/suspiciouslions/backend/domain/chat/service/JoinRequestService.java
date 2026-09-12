package com.suspiciouslions.backend.domain.chat.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.suspiciouslions.backend.domain.chat.dto.CreateJoinRequestResponse;
import com.suspiciouslions.backend.domain.chat.dto.JoinRequestStatusResponse;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoomJoinRequest;
import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomJoinRequestRepository;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage.UploadedProfileImage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorageException;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageValidation;

@Service
public class JoinRequestService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatRoomJoinRequestRepository joinRequestRepository;
	private final ProfileImageStorage profileImageStorage;
	private final TransactionTemplate transactionTemplate;

	public JoinRequestService(ChatRoomRepository chatRoomRepository,
			ChatRoomJoinRequestRepository joinRequestRepository,
			ProfileImageStorage profileImageStorage,
			PlatformTransactionManager transactionManager) {
		this.chatRoomRepository = chatRoomRepository;
		this.joinRequestRepository = joinRequestRepository;
		this.profileImageStorage = profileImageStorage;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public CreateJoinRequestResponse create(String inviteCode, String nickname, MultipartFile profileImage) {
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

	@Transactional(readOnly = true)
	public JoinRequestStatusResponse getStatus(Long requestId) {
		ChatRoomJoinRequest request = joinRequestRepository.findById(requestId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found"));
		if (request.getStatus() != JoinRequestStatus.ACCEPTED) {
			return new JoinRequestStatusResponse(request.getId(), request.getStatus(), null, null, null, null);
		}
		User user = request.getAssignedUser();
		return new JoinRequestStatusResponse(
				request.getId(),
				request.getStatus(),
				request.getChatRoom().getId(),
				user == null ? null : user.getId(),
				request.getNickname(),
				request.getProfileImageUrl()
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
}
