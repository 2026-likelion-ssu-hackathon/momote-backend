package com.suspiciouslions.backend.domain.chat.entity;

import java.time.OffsetDateTime;

import com.suspiciouslions.backend.domain.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_room_join_requests")
public class ChatRoomJoinRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "chat_room_id", nullable = false)
	private ChatRoom chatRoom;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JoinRequestStatus status;

	@Column(nullable = false)
	private String nickname;

	@Column(name = "profile_image_url")
	private String profileImageUrl;

	@Column(name = "profile_image_public_id")
	private String profileImagePublicId;

	@Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime requestedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assigned_user_id")
	private User assignedUser;

	protected ChatRoomJoinRequest() {
	}

	public ChatRoomJoinRequest(ChatRoom chatRoom, String nickname, String profileImageUrl,
			String profileImagePublicId, OffsetDateTime requestedAt) {
		this.chatRoom = chatRoom;
		this.status = JoinRequestStatus.PENDING;
		this.nickname = nickname;
		this.profileImageUrl = profileImageUrl;
		this.profileImagePublicId = profileImagePublicId;
		this.requestedAt = requestedAt;
	}

	public Long getId() {
		return id;
	}

	public ChatRoom getChatRoom() {
		return chatRoom;
	}

	public JoinRequestStatus getStatus() {
		return status;
	}

	public String getNickname() {
		return nickname;
	}

	public String getProfileImageUrl() {
		return profileImageUrl;
	}

	public String getProfileImagePublicId() {
		return profileImagePublicId;
	}

	public OffsetDateTime getRequestedAt() {
		return requestedAt;
	}

	public User getAssignedUser() {
		return assignedUser;
	}

	public void accept(User assignedUser) {
		ensurePending();
		this.status = JoinRequestStatus.ACCEPTED;
		this.assignedUser = assignedUser;
	}

	public void reject() {
		ensurePending();
		this.status = JoinRequestStatus.REJECTED;
	}

	private void ensurePending() {
		if (status != JoinRequestStatus.PENDING) {
			throw new IllegalStateException("Only a pending join request can be processed");
		}
	}
}
