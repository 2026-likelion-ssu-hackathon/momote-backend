package com.suspiciouslions.backend.domain.user.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProfileImageStorageConfig {

	@Bean
	ProfileImageStorage profileImageStorage(@Value("${CLOUDINARY_URL:}") String cloudinaryUrl) {
		return cloudinaryUrl.isBlank()
				? new UnavailableProfileImageStorage()
				: new CloudinaryProfileImageStorage(cloudinaryUrl);
	}
}
