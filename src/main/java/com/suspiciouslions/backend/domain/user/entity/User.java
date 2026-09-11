package com.suspiciouslions.backend.domain.user.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "auth_provider")
	private String authProvider;

	@Column(name = "provider_user_id")
	private String providerUserId;

	@Column
	private String nickname;

	@Column(name = "profile_image_url")
	private String profileImageUrl;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime updatedAt;

	protected User() {
	}

	public User(String authProvider, String providerUserId, String nickname, String profileImageUrl,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {
		this.authProvider = authProvider;
		this.providerUserId = providerUserId;
		this.nickname = nickname;
		this.profileImageUrl = profileImageUrl;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public Long getId() {
		return id;
	}

	public String getAuthProvider() {
		return authProvider;
	}

	public String getProviderUserId() {
		return providerUserId;
	}

	public String getNickname() {
		return nickname;
	}

	public String getProfileImageUrl() {
		return profileImageUrl;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void claimNickname(String nickname, OffsetDateTime updatedAt) {
		this.nickname = nickname;
		this.updatedAt = updatedAt;
	}
}
