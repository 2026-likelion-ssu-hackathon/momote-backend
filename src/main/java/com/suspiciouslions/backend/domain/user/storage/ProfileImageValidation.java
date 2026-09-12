package com.suspiciouslions.backend.domain.user.storage;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

public final class ProfileImageValidation {

	private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

	private ProfileImageValidation() {
	}

	public static void validate(MultipartFile profileImage) {
		if (profileImage == null) return;
		if (profileImage.isEmpty() || profileImage.getSize() > MAX_FILE_SIZE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Profile image must be non-empty and at most 5MB");
		}
		String contentType = profileImage.getContentType();
		if (contentType == null || !contentType.startsWith("image/")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Profile image must have an image content type");
		}
	}
}
