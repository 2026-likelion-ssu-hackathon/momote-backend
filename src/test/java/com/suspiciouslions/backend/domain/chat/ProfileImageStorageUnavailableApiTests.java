package com.suspiciouslions.backend.domain.chat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "CLOUDINARY_URL=")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ProfileImageStorageUnavailableApiTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Test
	void applicationStartsAndClaimWithoutImageSucceeds() throws Exception {
		ChatRoom room = createRoom();
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.header("X-User-Id", room.getUserA().getId())
				.param("nickname", "지민"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("지민"))
				.andExpect(jsonPath("$.profileImageUrl").isEmpty());
	}

	@Test
	void claimWithImageFailsOnlyWhenStorageIsUsed() throws Exception {
		ChatRoom room = createRoom();
		MockMultipartFile image = new MockMultipartFile(
				"profileImage", "profile.png", MediaType.IMAGE_PNG_VALUE, "image".getBytes());
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.file(image)
				.header("X-User-Id", room.getUserA().getId())
				.param("nickname", "지민"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void joinRequestWithoutImageSucceedsButImageRequestFails() throws Exception {
		ChatRoom room = createRoom();
		room.assignInviteCode("NOCLD1");
		chatRoomRepository.saveAndFlush(room);
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "NOCLD1").param("nickname", "지민"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"));

		MockMultipartFile image = new MockMultipartFile(
				"profileImage", "profile.png", MediaType.IMAGE_PNG_VALUE, "image".getBytes());
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image).param("inviteCode", "NOCLD1").param("nickname", "민지"))
				.andExpect(status().isBadGateway());
	}

	private ChatRoom createRoom() {
		OffsetDateTime now = OffsetDateTime.now();
		User user = userRepository.save(new User(null, null, null, null, now, now));
		return chatRoomRepository.save(new ChatRoom(user, null, null, RoomStatus.ACTIVE, now, null));
	}
}
