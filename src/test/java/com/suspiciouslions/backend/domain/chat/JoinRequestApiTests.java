package com.suspiciouslions.backend.domain.chat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
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
import com.suspiciouslions.backend.domain.user.entity.Gender;
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
	@Autowired private JoinRequestService joinRequestService;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetStorage() {
		storage.reset();
	}

	@Test
	void createsPendingRequestWithoutImageWithoutChangingParticipants() throws Exception {
		ChatRoom room = createOpenRoom("OPEN01");
		long userCount = userRepository.count();

		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "OPEN01").param("nickname", "지민").param("gender", "MALE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").isNumber())
				.andExpect(jsonPath("$.roomId").value(room.getId()))
				.andExpect(jsonPath("$.status").value("PENDING"));

		assertEquals(userCount, userRepository.count());
		assertNull(chatRoomRepository.findWithUsersById(room.getId()).orElseThrow().getUserB());
		assertEquals(Gender.MALE, joinRequestRepository.findAll().get(0).getGender());
		assertEquals(0, storage.uploadCount);
	}

	@Test
	void createsRequestWithImageAndPersistsUrlAndPublicId() throws Exception {
		ChatRoom room = createOpenRoom("IMAGE1");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image("image".getBytes()))
				.param("inviteCode", "IMAGE1").param("nickname", "지민").param("gender", "FEMALE"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));

		ChatRoomJoinRequest saved = joinRequestRepository.findAll().get(0);
		assertEquals(room.getId(), saved.getChatRoom().getId());
		assertEquals("https://example.test/profiles/join-1", saved.getProfileImageUrl());
		assertEquals("profiles/join-1", saved.getProfileImagePublicId());
		assertEquals(Gender.FEMALE, saved.getGender());
		assertEquals(1, storage.uploadCount);
	}

	@Test
	void createsRequestWithoutGenderAndRejectsInvalidGenderBeforeUploadOrSave() throws Exception {
		createOpenRoom("NOGEND");
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "NOGEND").param("nickname", "지민"))
				.andExpect(status().isOk());
		assertNull(joinRequestRepository.findAll().get(0).getGender());
		long requestCount = joinRequestRepository.count();

		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.file(image("image".getBytes()))
				.param("inviteCode", "NOGEND").param("nickname", "민상").param("gender", "OTHER"))
				.andExpect(status().isBadRequest());
		assertEquals(requestCount, joinRequestRepository.count());
		assertEquals(0, storage.uploadCount);
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
				.andExpect(jsonPath("$.profileImageUrl").doesNotExist())
				.andExpect(jsonPath("$.gender").doesNotExist());
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
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.required", not(hasItem("gender"))))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.properties.profileImage.type").value("string"))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.properties.profileImage.format").value("binary"))
				.andExpect(jsonPath("$.components.schemas.CreateJoinRequestDocument.properties.gender.enum",
						org.hamcrest.Matchers.contains("MALE", "FEMALE")))
				.andExpect(jsonPath("$.components.schemas.JoinRequestStatusResponse.properties.gender").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join-requests/{requestId}'].get.responses['200'].content['*/*'].schema['$ref']")
						.value("#/components/schemas/JoinRequestStatusResponse"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{roomId}/join-requests'].get.parameters[?(@.name == 'status')].schema.default")
						.value(hasItem("PENDING")))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{roomId}/join-requests'].get.parameters[*].name")
						.value(hasItem("X-User-Id")))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{roomId}/join-requests'].get.responses['403']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{roomId}/join-requests/{requestId}/accept'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{roomId}/join-requests/{requestId}/reject'].post.responses['404']").exists())
				.andExpect(jsonPath("$.components.schemas.JoinRequestListItemResponse.properties.requestId").exists())
				.andExpect(jsonPath("$.components.schemas.JoinRequestListItemResponse.properties.gender").exists())
				.andExpect(jsonPath("$.components.schemas.JoinRequestListItemResponse.properties.requestedAt.format").value("date-time"));
	}

	@Test
	void ownerListsPendingRequestsOldestFirstAndNonOwnerIsForbidden() throws Exception {
		ChatRoom room = createOpenRoom("LIST01");
		mockMvc.perform(get("/api/chat-rooms/{roomId}/join-requests", room.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		ChatRoomJoinRequest later = saveRequest(
				room, "나중", null, null, Gender.MALE, OffsetDateTime.now().plusMinutes(1));
		ChatRoomJoinRequest rejected = saveRequest(room, "거절", null, null, OffsetDateTime.now());
		rejected.reject();
		joinRequestRepository.saveAndFlush(rejected);
		ChatRoomJoinRequest earlier = saveRequest(room, "먼저", null, null, OffsetDateTime.now().minusMinutes(1));

		mockMvc.perform(get("/api/chat-rooms/{roomId}/join-requests", room.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].requestId").value(earlier.getId()))
				.andExpect(jsonPath("$[0].profileImageUrl").isEmpty())
				.andExpect(jsonPath("$[0].gender").isEmpty())
				.andExpect(jsonPath("$[1].requestId").value(later.getId()))
				.andExpect(jsonPath("$[1].gender").value("MALE"));
		mockMvc.perform(get("/api/chat-rooms/{roomId}/join-requests", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("status", "REJECTED"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].requestId").value(rejected.getId()));
		User outsider = userRepository.save(new User(null, null, "외부", null, OffsetDateTime.now(), OffsetDateTime.now()));
		mockMvc.perform(get("/api/chat-rooms/{roomId}/join-requests", room.getId())
				.header("X-User-Id", outsider.getId()))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/chat-rooms/{roomId}/join-requests", 999999L)
				.header("X-User-Id", outsider.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void ownerAcceptsRequestCreatesParticipantAndAutoRejectsOthers() throws Exception {
		ChatRoom room = createOpenRoom("ACCEPT");
		ChatRoomJoinRequest accepted = saveRequest(
				room, "지민", "https://example.test/jimin", "profiles/jimin", Gender.FEMALE, OffsetDateTime.now());
		ChatRoomJoinRequest other = saveRequest(room, "민지", "https://example.test/minji", "profiles/minji", OffsetDateTime.now().plusSeconds(1));
		ChatRoomJoinRequest another = saveRequest(room, "수진", "https://example.test/sujin", "profiles/sujin", OffsetDateTime.now().plusSeconds(2));
		long userCount = userRepository.count();
		storage.failDeletePublicId = "profiles/minji";

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", room.getId(), accepted.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").value(accepted.getId()))
				.andExpect(jsonPath("$.userId").isNumber())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		ChatRoom foundRoom = chatRoomRepository.findWithUsersById(room.getId()).orElseThrow();
		assertEquals(userCount + 1, userRepository.count());
		assertEquals("지민", foundRoom.getUserB().getNickname());
		assertEquals("https://example.test/jimin", foundRoom.getUserB().getProfileImageUrl());
		assertEquals(Gender.FEMALE, foundRoom.getUserB().getGender());
		ChatRoomJoinRequest foundAccepted = joinRequestRepository.findById(accepted.getId()).orElseThrow();
		assertEquals(JoinRequestStatus.ACCEPTED, foundAccepted.getStatus());
		assertEquals(foundRoom.getUserB().getId(), foundAccepted.getAssignedUser().getId());
		assertEquals(JoinRequestStatus.REJECTED, joinRequestRepository.findById(other.getId()).orElseThrow().getStatus());
		assertEquals(JoinRequestStatus.REJECTED, joinRequestRepository.findById(another.getId()).orElseThrow().getStatus());
		assertEquals(List.of("profiles/minji", "profiles/sujin"), storage.deletedPublicIds);
		assertEquals(0, storage.uploadCount);

		mockMvc.perform(get("/api/chat-rooms/join-requests/{requestId}", accepted.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.roomId").value(room.getId()))
				.andExpect(jsonPath("$.userId").value(foundRoom.getUserB().getId()))
				.andExpect(jsonPath("$.nickname").value("지민"))
				.andExpect(jsonPath("$.profileImageUrl").value("https://example.test/jimin"))
				.andExpect(jsonPath("$.gender").value("FEMALE"));
		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", room.getId())
				.header("X-User-Id", foundRoom.getUserB().getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.partner.userId").value(room.getUserA().getId()));
		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", room.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.partner.userId").value(foundRoom.getUserB().getId()))
				.andExpect(jsonPath("$.partner.gender").value("FEMALE"));
	}

	@Test
	void acceptingRequestWithoutGenderCreatesUserWithNullGender() throws Exception {
		ChatRoom room = createOpenRoom("ACCNUL");
		ChatRoomJoinRequest request = saveRequest(room, "지민", null, null, OffsetDateTime.now());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", room.getId(), request.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk());
		ChatRoom acceptedRoom = chatRoomRepository.findWithUsersById(room.getId()).orElseThrow();
		assertNull(acceptedRoom.getUserB().getGender());
	}

	@Test
	void rejectCommitsBeforeImageCleanupAndAllowsAnotherRequest() throws Exception {
		ChatRoom room = createOpenRoom("REJECT");
		ChatRoomJoinRequest request = saveRequest(
				room, "지민", "https://example.test/jimin", "profiles/jimin", Gender.MALE, OffsetDateTime.now());
		long userCount = userRepository.count();
		storage.failDelete = true;

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/reject", room.getId(), request.getId())
				.header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

		assertEquals(JoinRequestStatus.REJECTED, joinRequestRepository.findById(request.getId()).orElseThrow().getStatus());
		assertNull(chatRoomRepository.findWithUsersById(room.getId()).orElseThrow().getUserB());
		assertEquals(userCount, userRepository.count());
		assertEquals("REJECT", chatRoomRepository.findById(room.getId()).orElseThrow().getInviteCode());
		assertEquals(List.of("profiles/jimin"), storage.deletedPublicIds);
		mockMvc.perform(get("/api/chat-rooms/join-requests/{requestId}", request.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.gender").doesNotExist());
		ChatRoomJoinRequest noImage = saveRequest(room, "이미지없음", null, null, OffsetDateTime.now().plusSeconds(1));
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/reject", room.getId(), noImage.getId())
				.header("X-User-Id", room.getUserA().getId())).andExpect(status().isOk());
		assertEquals(List.of("profiles/jimin"), storage.deletedPublicIds);
		mockMvc.perform(multipart("/api/chat-rooms/join-requests")
				.param("inviteCode", "REJECT").param("nickname", "민지"))
				.andExpect(status().isOk());
	}

	@Test
	void processingRejectsWrongRoomNonOwnerProcessedRequestAndFullRoom() throws Exception {
		ChatRoom room = createOpenRoom("CHECK1");
		ChatRoom otherRoom = createOpenRoom("CHECK2");
		ChatRoomJoinRequest request = saveRequest(room, "지민", null, null, OffsetDateTime.now());
		User outsider = otherRoom.getUserA();
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", room.getId(), request.getId())
				.header("X-User-Id", outsider.getId())).andExpect(status().isForbidden());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", otherRoom.getId(), request.getId())
				.header("X-User-Id", otherRoom.getUserA().getId())).andExpect(status().isNotFound());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/reject", room.getId(), request.getId())
				.header("X-User-Id", outsider.getId())).andExpect(status().isForbidden());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/reject", otherRoom.getId(), request.getId())
				.header("X-User-Id", otherRoom.getUserA().getId())).andExpect(status().isNotFound());
		request.reject();
		joinRequestRepository.saveAndFlush(request);
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", room.getId(), request.getId())
				.header("X-User-Id", room.getUserA().getId())).andExpect(status().isConflict());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/reject", room.getId(), request.getId())
				.header("X-User-Id", room.getUserA().getId())).andExpect(status().isConflict());
		ChatRoomJoinRequest fullRequest = saveRequest(otherRoom, "민지", null, null, OffsetDateTime.now());
		otherRoom.assignUserB(userRepository.save(new User(null, null, "참가자", null, OffsetDateTime.now(), OffsetDateTime.now())));
		chatRoomRepository.saveAndFlush(otherRoom);
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
				"/api/chat-rooms/{roomId}/join-requests/{requestId}/accept", otherRoom.getId(), fullRequest.getId())
				.header("X-User-Id", otherRoom.getUserA().getId())).andExpect(status().isConflict());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentAcceptsOfDifferentRequestsAllowExactlyOne() throws Exception {
		ChatRoom room = createOpenRoom("RACE01");
		ChatRoomJoinRequest first = saveRequest(room, "첫째", null, null, OffsetDateTime.now());
		ChatRoomJoinRequest second = saveRequest(room, "둘째", null, null, OffsetDateTime.now().plusSeconds(1));
		try {
			List<Integer> statuses = runConcurrently(
					() -> joinRequestService.accept(room.getId(), first.getId(), room.getUserA().getId()),
					() -> joinRequestService.accept(room.getId(), second.getId(), room.getUserA().getId()));
			assertEquals(List.of(200, 409), statuses);
			assertEquals(1, joinRequestRepository.findAll().stream()
					.filter(request -> request.getStatus() == JoinRequestStatus.ACCEPTED).count());
			assertEquals(1, joinRequestRepository.findAll().stream()
					.filter(request -> request.getStatus() == JoinRequestStatus.REJECTED).count());
		} finally {
			deleteCommittedRoom(room.getId(), room.getUserA().getId());
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentAcceptsOfSameRequestAllowExactlyOne() throws Exception {
		ChatRoom room = createOpenRoom("RACE02");
		ChatRoomJoinRequest request = saveRequest(room, "지민", null, null, OffsetDateTime.now());
		try {
			List<Integer> statuses = runConcurrently(
					() -> joinRequestService.accept(room.getId(), request.getId(), room.getUserA().getId()),
					() -> joinRequestService.accept(room.getId(), request.getId(), room.getUserA().getId()));
			assertEquals(List.of(200, 409), statuses);
			assertEquals(JoinRequestStatus.ACCEPTED, joinRequestRepository.findById(request.getId()).orElseThrow().getStatus());
		} finally {
			deleteCommittedRoom(room.getId(), room.getUserA().getId());
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentAcceptAndRejectProduceOneFinalState() throws Exception {
		ChatRoom room = createOpenRoom("RACE03");
		ChatRoomJoinRequest request = saveRequest(room, "지민", null, null, OffsetDateTime.now());
		try {
			List<Integer> statuses = runConcurrently(
					() -> joinRequestService.accept(room.getId(), request.getId(), room.getUserA().getId()),
					() -> joinRequestService.reject(room.getId(), request.getId(), room.getUserA().getId()));
			assertEquals(List.of(200, 409), statuses);
			JoinRequestStatus finalStatus = joinRequestRepository.findById(request.getId()).orElseThrow().getStatus();
			org.junit.jupiter.api.Assertions.assertTrue(
					finalStatus == JoinRequestStatus.ACCEPTED || finalStatus == JoinRequestStatus.REJECTED);
		} finally {
			deleteCommittedRoom(room.getId(), room.getUserA().getId());
		}
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

	private ChatRoomJoinRequest saveRequest(ChatRoom room, String nickname, String imageUrl,
			String publicId, OffsetDateTime requestedAt) {
		return saveRequest(room, nickname, imageUrl, publicId, null, requestedAt);
	}

	private ChatRoomJoinRequest saveRequest(ChatRoom room, String nickname, String imageUrl,
			String publicId, Gender gender, OffsetDateTime requestedAt) {
		return joinRequestRepository.saveAndFlush(
				new ChatRoomJoinRequest(room, nickname, imageUrl, publicId, gender, requestedAt));
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
		return new JoinRequestService(rooms, requests, mock(UserRepository.class), storage, transactionManager);
	}

	private List<Integer> runConcurrently(Supplier<?> firstAction, Supplier<?> secondAction) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
					() -> runProcessing(firstAction, ready, start), executor);
			CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
					() -> runProcessing(secondAction, ready, start), executor);
			org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			return List.of(first.get(), second.get()).stream().sorted().toList();
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private int runProcessing(Supplier<?> action, CountDownLatch ready, CountDownLatch start) {
		try {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Processing did not start");
			action.get();
			return 200;
		} catch (org.springframework.web.server.ResponseStatusException exception) {
			return exception.getStatusCode().value();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private void deleteCommittedRoom(Long roomId, Long ownerId) {
		Long participantId = jdbcTemplate.queryForObject(
				"SELECT user_b_id FROM chat_rooms WHERE id = ?", Long.class, roomId);
		jdbcTemplate.update("DELETE FROM chat_room_join_requests WHERE chat_room_id = ?", roomId);
		jdbcTemplate.update("DELETE FROM chat_rooms WHERE id = ?", roomId);
		if (participantId != null) jdbcTemplate.update("DELETE FROM users WHERE id = ?", participantId);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
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
		List<String> deletedPublicIds = new java.util.ArrayList<>();
		boolean failUpload;
		boolean failDelete;
		String failDeletePublicId;

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
			deletedPublicIds.add(publicId);
			if (failDelete || Objects.equals(failDeletePublicId, publicId)) {
				throw new ProfileImageStorageException("fake delete failure");
			}
		}

		void reset() {
			uploadCount = 0;
			deleteCount = 0;
			deletedPublicId = null;
			deletedPublicIds.clear();
			failUpload = false;
			failDelete = false;
			failDeletePublicId = null;
		}
	}
}
