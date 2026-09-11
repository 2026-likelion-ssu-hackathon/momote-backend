package com.suspiciouslions.backend.domain.chat.entity;

import java.time.LocalDate;
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
@Table(name = "chat_rooms")
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_a_id", nullable = false)
	private User userA;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_b_id")
	private User userB;

	@Column(name = "invite_code", unique = true)
	private String inviteCode;

	@Column(name = "relationship_started_on")
	private LocalDate relationshipStartedOn;

	@Enumerated(EnumType.STRING)
	@Column(name = "room_status", nullable = false)
	private RoomStatus roomStatus;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;

	@Column(name = "ended_at", columnDefinition = "timestamptz")
	private OffsetDateTime endedAt;

	protected ChatRoom() {
	}

	public ChatRoom(User userA, User userB, LocalDate relationshipStartedOn, RoomStatus roomStatus,
			OffsetDateTime createdAt, OffsetDateTime endedAt) {
		this.userA = userA;
		this.userB = userB;
		this.relationshipStartedOn = relationshipStartedOn;
		this.roomStatus = roomStatus;
		this.createdAt = createdAt;
		this.endedAt = endedAt;
	}

	public Long getId() {
		return id;
	}

	public User getUserA() {
		return userA;
	}

	public User getUserB() {
		return userB;
	}

	public String getInviteCode() {
		return inviteCode;
	}

	public void assignUserB(User userB) {
		this.userB = userB;
	}

	public void assignInviteCode(String inviteCode) {
		this.inviteCode = inviteCode;
	}

	public LocalDate getRelationshipStartedOn() {
		return relationshipStartedOn;
	}

	public RoomStatus getRoomStatus() {
		return roomStatus;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getEndedAt() {
		return endedAt;
	}
}
