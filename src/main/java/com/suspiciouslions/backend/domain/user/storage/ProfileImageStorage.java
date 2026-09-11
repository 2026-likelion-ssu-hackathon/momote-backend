package com.suspiciouslions.backend.domain.user.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ProfileImageStorage {

	UploadedProfileImage upload(MultipartFile file);

	void delete(String publicId);

	record UploadedProfileImage(String publicId, String secureUrl) {
	}
}
