package com.suspiciouslions.backend.domain.user.storage;

public class ProfileImageStorageException extends RuntimeException {

	public ProfileImageStorageException(String message) {
		super(message);
	}

	public ProfileImageStorageException(String message, Throwable cause) {
		super(message, cause);
	}
}
