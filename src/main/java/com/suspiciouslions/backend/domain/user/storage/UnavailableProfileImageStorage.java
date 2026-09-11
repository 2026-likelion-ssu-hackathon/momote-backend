package com.suspiciouslions.backend.domain.user.storage;

import org.springframework.web.multipart.MultipartFile;

public class UnavailableProfileImageStorage implements ProfileImageStorage {

	@Override
	public UploadedProfileImage upload(MultipartFile file) {
		throw new ProfileImageStorageException("Profile image storage is not configured");
	}

	@Override
	public void delete(String publicId) {
		// Nothing was uploaded when storage is unavailable.
	}
}
