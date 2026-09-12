package com.suspiciouslions.backend.domain.chat;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoomJoinRequest;
import com.suspiciouslions.backend.domain.chat.entity.JoinRequestStatus;
import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomJoinRequestRepository;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.chat.service.JoinRequestService;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage.UploadedProfileImage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorageException;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@Import(JoinRequestApiTests.StorageTestConfig.class)
class JoinRequestApiTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired private MockMvc mockMvc;
	@Autowired private UserRepository userRepository;
	@Autowired private ChatRoomRepository chatRoomRepository;
	@Autowired private ChatRoomJoinRequestRepository joinRequestRepository;
	@Autowired private FakeStorage storage;

	@BeforeEach
	void resetStorage() {
		storage.reset();
	}

	@Test
	void createsPendingRequestWithoutImageWithoutChangingParticipants() throws Exception {
		ChatRoom room = createOpenRoom("OPEN01");
		long userCount = userRepository.count();

		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "OPEN01").param("nickname", "지민"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").isNumber())
				.andExpect(jsonPath("$.roomId").value(room.getId()))
				.andExpect(jsonPath("$.status").value("PENDING"));

		assertEquals(userCount, userRepository.count());
		assertNull(chatRoomRepository.findWithUsersById(room.getId()).orElseThrow().getUserB());
		assertEquals(0, storage.uploadCount);
	}

	@Test
	void createsRequestWithImageAndPersistsUrlAndPublicId() throws Exception {
		ChatRoom room = createOpenRoom("IMAGE1");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image("image".getBytes()))
				.param("inviteCode", "IMAGE1").param("nickname", "지민"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

		ChatRoomJoinRequest saved = joinRequestRepository.findAll().get(0);
		assertEquals(room.getId(), saved.getChatRoom().getId());
		assertEquals("https://example.test/profiles/join-1", saved.getProfileImageUrl());
		assertEquals("profiles/join-1", saved.getProfileImagePublicId());
		assertEquals(1, storage.uploadCount);
	}

	@Test
	void rejectsUnknownCodeFullRoomAndInvalidNicknameBeforeUpload() throws Exception {
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image("image".getBytes())).param("inviteCode", "NONE00").param("nickname", "지민"))
				.andExpect(status().isNotFound());
		ChatRoom fullRoom = createFullRoom("FULL01");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image("image".getBytes())).param("inviteCode", fullRoom.getInviteCode()).param("nickname", "지민"))
				.andExpect(status().isConflict());
		mockMvc.perform(multipart("/api/chat-rooms/join-requests").param("inviteCode", "FULL01"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/chat-rooms/join-requests").param("inviteCode", "FULL01").param("nickname", "   "))
				.andExpect(status().isBadRequest());
		assertEquals(0, storage.uploadCount);
	}

	@Test
	void rejectsInvalidImagesBeforeUpload() throws Exception {
		createOpenRoom("VALID1");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests").file(image(new byte[0]))
				.param("inviteCode", "VALID1").param("nickname", "지민"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(new MockMultipartFile("profileImage", "x.txt", MediaType.TEXT_PLAIN_VALUE, "x".getBytes()))
				.param("inviteCode", "VALID1").param("nickname", "지민"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/chat-rooms/join-requests").file(image(new byte[5 * 1024 * 1024 + 1]))
				.param("inviteCode", "VALID1").param("nickname", "지민"))
				.andExpect(status().isBadRequest());
		assertEquals(0, storage.uploadCount);
	}

	@Test
	void uploadFailureReturnsBadGatewayAndDoesNotSaveRequest() throws Exception {
		createOpenRoom("FAIL01");
		storage.failUpload = true;
		mockMvc.perform(multipart("/api/chat-rooms/join-requests").file(image("image".getBytes()))
				.param("inviteCode", "FAIL01").param("nickname", "지민"))
				.andExpect(status().isBadGateway());
		assertEquals(0, joinRequestRepository.count());
	}

	@Test
	void pendingStatusOmitsAcceptedFieldsAndUnknownRequestIsNotFound() throws Exception {
		createOpenRoom("STATE1");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "STATE1").param("nickname", "지민"))
				.andExpect(status().isOk());
		Long requestId = joinRequestRepository.findAll().get(0).getId();
		mockMvc.perform(get("/api/chat-rooms/join-requests/{requestId}", requestId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").value(requestId))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.roomId").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andExpect(jsonPath("$.nickname").doesNotExist())
				.andExpect(jsonPath("$.profileImageUrl").doesNotExist());
		mockMvc.perform(get("/api/chat-rooms/join-requests/{requestId}", 999999L))
				.andExpect(status().isNotFound());
	}

	@Test
	void databaseFailureCompensatesUploadedImage() {
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException("commit failed");
		JoinRequestService service = serviceWhoseCommitFails(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.create("OPEN01", "지민", image("image".getBytes())));

		assertSame(databaseFailure, thrown);
		assertEquals(1, storage.deleteCount);
		assertEquals("profiles/join-1", storage.deletedPublicId);
	}

	@Test
	void compensationDeleteFailureDoesNotReplaceDatabaseFailure() {
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException("commit failed");
		storage.failDelete = true;
		JoinRequestService service = serviceWhoseCommitFails(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> service.create("OPEN01", "지민", image("image".getBytes())));

		assertSame(databaseFailure, thrown);
		assertEquals(1, storage.deleteCount);
		assertEquals("profiles/join-1", storage.deletedPublicId);
	}

	@Test
	void openApiDescribesMultipartAndStatusResponses() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.responses['404']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.responses['502']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.responses['200'].content['*/*'].schema['$ref']")
						.value("#/components/schemas/CreateJoinRequestResponse"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests'].post.requestBody.content['multipart/form-data'].schema['$ref']")
						.value("#/components/schemas/CreateJoinRequestDocument"))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.required", hasItem("inviteCode")))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.required", hasItem("nickname")))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.required", not(hasItem("profileImage"))))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.properties.profileImage.type").value("string"))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.properties.profileImage.format").value("binary"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests/{requestId}'].get.responses['200'].content['*/*'].schema['$ref']")
						.value("#/components/schemas/JoinRequestStatusResponse"));
	}

	private ChatRoom createOpenRoom(String inviteCode) {
		OffsetDateTime now = OffsetDateTime.now();
		User owner = userRepository.save(new User(null, null, "방장", null, now, now));
		ChatRoom room = new ChatRoom(owner, null, null, RoomStatus.ACTIVE, now, null);
		room.assignInviteCode(inviteCode);
		return chatRoomRepository.saveAndFlush(room);
	}

	private ChatRoom createFullRoom(String inviteCode) {
		ChatRoom room = createOpenRoom(inviteCode);
		OffsetDateTime now = OffsetDateTime.now();
		room.assignUserB(userRepository.save(new User(null, null, "참가자", null, now, now)));
		return chatRoomRepository.saveAndFlush(room);
	}

	private MockMultipartFile image(byte[] content) {
		return new MockMultipartFile("profileImage", "profile.png", MediaType.IMAGE_PNG_VALUE, content);
	}

	private JoinRequestService serviceWhoseCommitFails(RuntimeException failure) {
		ChatRoom room = mock(ChatRoom.class);
		when(room.getId()).thenReturn(1L);
		when(room.getUserB()).thenReturn(null);
		ChatRoomRepository rooms = mock(ChatRoomRepository.class);
		when(rooms.findWithUsersByInviteCode("OPEN01")).thenReturn(Optional.of(room));
		ChatRoomJoinRequest request = mock(ChatRoomJoinRequest.class);
		when(request.getId()).thenReturn(1L);
		when(request.getStatus()).thenReturn(JoinRequestStatus.PENDING);
		ChatRoomJoinRequestRepository requests = mock(ChatRoomJoinRequestRepository.class);
		when(requests.saveAndFlush(any())).thenReturn(request);
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		doThrow(failure).when(transactionManager).commit(any());
		return new JoinRequestService(rooms, requests, storage, transactionManager);
	}

	@TestConfiguration
	static class StorageTestConfig {
		@Bean
		@Primary
		FakeStorage joinRequestFakeStorage() {
			return new FakeStorage();
		}
	}

	static class FakeStorage implements ProfileImageStorage {
		int uploadCount;
		int deleteCount;
		String deletedPublicId;
		boolean failUpload;
		boolean failDelete;

		@Override
		public UploadedProfileImage upload(org.springframework.web.multipart.MultipartFile file) {
			uploadCount++;
			if (failUpload) throw new ProfileImageStorageException("fake upload failure");
			return new UploadedProfileImage("profiles/join-" + uploadCount,
					"https://example.test/profiles/join-" + uploadCount);
		}

		@Override
		public void delete(String publicId) {
			deleteCount++;
			deletedPublicId = publicId;
			if (failDelete) throw new ProfileImageStorageException("fake delete failure");
		}

		void reset() {
			uploadCount = 0;
			deleteCount = 0;
			deletedPublicId = null;
			failUpload = false;
			failDelete = false;
		}
	}
}
