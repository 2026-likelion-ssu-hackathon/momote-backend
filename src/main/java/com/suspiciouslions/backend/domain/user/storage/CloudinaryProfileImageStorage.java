package com.suspiciouslions.backend.domain.user.storage;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

public class CloudinaryProfileImageStorage implements ProfileImageStorage {

	private final Cloudinary cloudinary;

	public CloudinaryProfileImageStorage(String cloudinaryUrl) {
		this.cloudinary = new Cloudinary(cloudinaryUrl);
	}

	@Override
	public UploadedProfileImage upload(MultipartFile file) {
		try {
			Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
					"folder", "profiles",
					"public_id", UUID.randomUUID().toString(),
					"resource_type", "image"
			));
			Object publicId = result.get("public_id");
			Object secureUrl = result.get("secure_url");
			if (!(publicId instanceof String id) || id.isBlank()
					|| !(secureUrl instanceof String url) || !url.startsWith("https://")) {
				throw new ProfileImageStorageException("Cloudinary returned an invalid upload response");
			}
			return new UploadedProfileImage(id, url);
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof ProfileImageStorageException storageException) throw storageException;
			throw new ProfileImageStorageException("Cloudinary upload failed", exception);
		}
	}

	@Override
	public void delete(String publicId) {
		try {
			cloudinary.uploader().destroy(publicId, ObjectUtils.asMap("resource_type", "image"));
		} catch (IOException | RuntimeException exception) {
			// Compensation failure must not hide the database failure that triggered it.
		}
	}
}
