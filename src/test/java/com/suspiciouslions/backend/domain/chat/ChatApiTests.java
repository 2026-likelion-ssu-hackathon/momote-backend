package com.suspiciouslions.backend.domain.chat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.Message;
import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.chat.repository.MessageRepository;
import com.suspiciouslions.backend.domain.chat.service.ChatService;
import com.suspiciouslions.backend.domain.chat.dto.CreateChatRoomResponse;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorage.UploadedProfileImage;
import com.suspiciouslions.backend.domain.user.storage.ProfileImageStorageException;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@Import(ChatApiTests.ProfileImageStorageTestConfig.class)
class ChatApiTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private MessageRepository messageRepository;

	@Autowired
	private ChatService chatService;

	@Autowired
	private FakeProfileImageStorage profileImageStorage;

	@BeforeEach
	void resetProfileImageStorage() {
		profileImageStorage.reset();
	}

	@Test
	void participantGetsChatRoomAndPartner() throws Exception {
		TestContext context = createTestContext();

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chatRoomId").value(context.chatRoom().getId()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.relationshipStartedOn").value("2026-08-01"))
				.andExpect(jsonPath("$.partner.userId").value(context.userB().getId()))
				.andExpect(jsonPath("$.partner.nickname").value("사용자 B"))
				.andExpect(jsonPath("$.partner.profileImageUrl").value("https://example.com/b.png"));
	}

	@Test
	void nonParticipantCannotGetChatRoom() throws Exception {
		TestContext context = createTestContext();
		User outsider = saveUser("외부 사용자", null);

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", context.chatRoom().getId())
					.header("X-User-Id", outsider.getId()))
				.andExpect(status().isForbidden());
	}

	@Test
	void createsInviteRoomWithOneTemporaryParticipantAndCanBeClaimed() throws Exception {
		String response = mockMvc.perform(post("/api/chat-rooms"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roomId").isNumber())
				.andExpect(jsonPath("$.userId").isNumber())
				.andExpect(jsonPath("$.inviteCode").value(org.hamcrest.Matchers.matchesPattern("[A-Z0-9]{6}")))
				.andReturn().getResponse().getContentAsString();
		ChatRoom room = chatRoomRepository.findAll().stream()
				.filter(candidate -> response.contains("\"roomId\":" + candidate.getId()))
				.findFirst().orElseThrow();

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", room.getId()).header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.partner").doesNotExist());
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "지민"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("지민"))
				.andExpect(jsonPath("$.profileImageUrl").isEmpty());
	}

	@Test
	void claimWithImageStoresSecureUrlAndReturnsItToPartner() throws Exception {
		ChatRoom room = createInviteRoom();
		User partner = saveUser(null, null);
		room.assignUserB(partner);
		chatRoomRepository.saveAndFlush(room);
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "지민")
				.file(imageFile("image-data".getBytes())).contentType(MediaType.MULTIPART_FORM_DATA)
				.characterEncoding("UTF-8"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profileImageUrl").value("https://example.test/profiles/image-1"));
		org.junit.jupiter.api.Assertions.assertEquals(1, profileImageStorage.uploadCount);

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}", room.getId())
				.header("X-User-Id", partner.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.partner.profileImageUrl").value("https://example.test/profiles/image-1"));
	}

	@Test
	void claimWithoutImageDoesNotCallStorage() throws Exception {
		ChatRoom room = createInviteRoom();
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "지민"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.profileImageUrl").isEmpty());
		org.junit.jupiter.api.Assertions.assertEquals(0, profileImageStorage.uploadCount);
	}

	@Test
	void invalidProfileImagesAreRejectedBeforeStorage() throws Exception {
		ChatRoom room = createInviteRoom();
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "지민")
				.file(imageFile(new byte[0])))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "지민")
				.file(new MockMultipartFile("profileImage", "note.txt", MediaType.TEXT_PLAIN_VALUE, "not-an-image".getBytes())))
				.andExpect(status().isBadRequest());
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "지민")
				.file(imageFile(new byte[5 * 1024 * 1024 + 1])))
				.andExpect(status().isBadRequest());
		org.junit.jupiter.api.Assertions.assertEquals(0, profileImageStorage.uploadCount);
	}

	@Test
	void unauthorizedOrAlreadyClaimedParticipantDoesNotUploadImage() throws Exception {
		ChatRoom room = createInviteRoom();
		User outsider = saveUser(null, null);
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", outsider.getId()).param("nickname", "외부")
				.file(imageFile("image".getBytes())))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "지민"))
				.andExpect(status().isOk());
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "재등록")
				.file(imageFile("image".getBytes())))
				.andExpect(status().isConflict());
		org.junit.jupiter.api.Assertions.assertEquals(0, profileImageStorage.uploadCount);
	}

	@Test
	void uploadFailureDoesNotClaimNickname() throws Exception {
		ChatRoom room = createInviteRoom();
		profileImageStorage.failUpload = true;
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.header("X-User-Id", room.getUserA().getId()).param("nickname", "지민")
				.file(imageFile("image".getBytes())))
				.andExpect(status().isBadGateway());
		org.junit.jupiter.api.Assertions.assertNull(userRepository.findById(room.getUserA().getId()).orElseThrow().getNickname());
	}

	@Test
	void databaseCommitFailureDeletesUploadedImage() {
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException("commit failed");
		ChatService failingService = claimServiceWhoseCommitFails(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> failingService.claimNickname(1L, 1L, "지민", imageFile("image".getBytes())));

		assertSame(databaseFailure, thrown);
		assertEquals(1, profileImageStorage.deleteCount);
		assertEquals("profiles/image-1", profileImageStorage.deletedPublicId);
	}

	@Test
	void compensationDeleteFailureDoesNotReplaceDatabaseFailure() {
		DataIntegrityViolationException databaseFailure = new DataIntegrityViolationException("commit failed");
		profileImageStorage.failDelete = true;
		ChatService failingService = claimServiceWhoseCommitFails(databaseFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> failingService.claimNickname(1L, 1L, "지민", imageFile("image".getBytes())));

		assertSame(databaseFailure, thrown);
		assertEquals(1, profileImageStorage.deleteCount);
		assertEquals("profiles/image-1", profileImageStorage.deletedPublicId);
	}

	@Test
	void maxUploadSizeExceptionMapsToBadRequest() throws Exception {
		ChatRoom room = createInviteRoom();
		profileImageStorage.failMaxUpload = true;
		mockMvc.perform(multipart("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.file(imageFile("image".getBytes()))
				.header("X-User-Id", room.getUserA().getId())
				.param("nickname", "지민"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void inviteCodeJoinsOnlyOneSecondParticipant() throws Exception {
		ChatRoom room = createInviteRoom();
		mockMvc.perform(post("/api/chat-rooms/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"inviteCode\":\"" + room.getInviteCode() + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(room.getId()));
		mockMvc.perform(post("/api/chat-rooms/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"inviteCode\":\"" + room.getInviteCode() + "\"}"))
				.andExpect(status().isConflict());
		mockMvc.perform(post("/api/chat-rooms/join").contentType(MediaType.APPLICATION_JSON)
				.content("{\"inviteCode\":\"NOPE00\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void inviteCodeIsUniqueInDatabase() {
		ChatRoom first = createInviteRoom();
		User otherUser = saveUser(null, null);
		ChatRoom duplicate = new ChatRoom(otherUser, null, null, RoomStatus.ACTIVE, time(0), null);
		duplicate.assignInviteCode(first.getInviteCode());
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
				() -> chatRoomRepository.saveAndFlush(duplicate));
	}

	@Test
	void participantClaimRejectsOtherRoomsUserAndDuplicateClaim() throws Exception {
		ChatRoom room = createInviteRoom();
		User otherRoomUser = saveUser(null, null);
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", otherRoomUser.getId()).param("nickname", "외부"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "지민"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "지민2"))
				.andExpect(status().isConflict());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentJoinsDoNotExceedTwoParticipants() throws Exception {
		CreateChatRoomResponse created = chatService.createChatRoom();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			CompletableFuture<JoinAttempt> first = CompletableFuture.supplyAsync(
					() -> tryJoin(created.inviteCode(), ready, start), executor);
			CompletableFuture<JoinAttempt> second = CompletableFuture.supplyAsync(
					() -> tryJoin(created.inviteCode(), ready, start), executor);
			org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			List<Integer> statuses = List.of(first.get().status(), second.get().status()).stream().sorted().toList();
			org.junit.jupiter.api.Assertions.assertEquals(List.of(200, 409), statuses);
			ChatRoom room = chatRoomRepository.findWithUsersById(created.roomId()).orElseThrow();
			org.junit.jupiter.api.Assertions.assertNotNull(room.getUserB());
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
			chatRoomRepository.findWithUsersById(created.roomId()).ifPresent(room -> {
				Long secondUserId = room.getUserB() == null ? null : room.getUserB().getId();
				chatRoomRepository.delete(room);
				userRepository.deleteById(created.userId());
				if (secondUserId != null) userRepository.deleteById(secondUserId);
			});
		}
	}

	@Test
	void oneParticipantRoomRejectsMessageAndSafelyReturnsEmptyAiAndEmotionResults() throws Exception {
		ChatRoom room = createInviteRoom();
		Long userId = room.getUserA().getId();
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/messages", room.getId())
				.header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON)
				.content(messageRequest("waiting-room", "안녕하세요", time(1))))
				.andExpect(status().isConflict());
		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/ai-results", room.getId()).header("X-User-Id", userId))
				.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/emotion-analyses", room.getId()).header("X-User-Id", userId))
				.andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void nicknameClaimRejectsMissingOrBlankNickname() throws Exception {
		ChatRoom room = createInviteRoom();
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/participants/claim", room.getId())
				.contentType(MediaType.MULTIPART_FORM_DATA).header("X-User-Id", room.getUserA().getId()).param("nickname", "   "))
				.andExpect(status().isBadRequest());
	}

	@Test
	void messageIsSavedAndReturned() throws Exception {
		TestContext context = createTestContext();

		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(messageRequest("client-1", "안녕하세요", time(1))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messageId").isNumber())
				.andExpect(jsonPath("$.chatRoomId").value(context.chatRoom().getId()))
				.andExpect(jsonPath("$.senderId").value(context.userA().getId()))
				.andExpect(jsonPath("$.clientMessageId").value("client-1"))
				.andExpect(jsonPath("$.content").value("안녕하세요"))
				.andExpect(jsonPath("$.sentAt").value("2026-08-18T03:01:00Z"));

		org.junit.jupiter.api.Assertions.assertEquals(1, messageRepository.count());
	}

	@Test
	void retryWithSameClientMessageIdReturnsExistingMessage() throws Exception {
		TestContext context = createTestContext();

		String firstResponse = mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(messageRequest("same-client-id", "첫 요청", time(1))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(messageRequest("same-client-id", "재전송 본문", time(2))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").value("첫 요청"));

		org.junit.jupiter.api.Assertions.assertEquals(1, messageRepository.count());
		org.junit.jupiter.api.Assertions.assertTrue(firstResponse.contains("\"content\":\"첫 요청\""));
	}

	@Test
	void blankMessageContentIsRejected() throws Exception {
		TestContext context = createTestContext();

		mockMvc.perform(post("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.contentType(MediaType.APPLICATION_JSON)
					.content(messageRequest("blank-message", "   ", time(1))))
				.andExpect(status().isBadRequest());

		org.junit.jupiter.api.Assertions.assertEquals(0, messageRepository.count());
	}

	@Test
	void latestMessagesUseLimitAndReturnOldestToNewest() throws Exception {
		TestContext context = createTestContext();
		List<Message> messages = saveMessages(context, 5);

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("size", "3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].messageId").value(messages.get(2).getId()))
				.andExpect(jsonPath("$[2].messageId").value(messages.get(4).getId()));
	}

	@Test
	void beforeCursorReturnsOlderMessagesOldestToNewest() throws Exception {
		TestContext context = createTestContext();
		List<Message> messages = saveMessages(context, 5);

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("beforeMessageId", messages.get(4).getId().toString())
					.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].messageId").value(messages.get(2).getId()))
				.andExpect(jsonPath("$[1].messageId").value(messages.get(3).getId()));
	}

	@Test
	void afterCursorReturnsNewerMessagesOldestToNewest() throws Exception {
		TestContext context = createTestContext();
		List<Message> messages = saveMessages(context, 5);

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("afterMessageId", messages.get(1).getId().toString())
					.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].messageId").value(messages.get(2).getId()))
				.andExpect(jsonPath("$[1].messageId").value(messages.get(3).getId()));
	}

	@Test
	void bothCursorsAreRejected() throws Exception {
		TestContext context = createTestContext();

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("beforeMessageId", "10")
					.param("afterMessageId", "20"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void sizeOutsideAllowedRangeIsRejected() throws Exception {
		TestContext context = createTestContext();

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("size", "0"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/chat-rooms/{chatRoomId}/messages", context.chatRoom().getId())
					.header("X-User-Id", context.userA().getId())
					.param("size", "101"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void chatEndpointsAreDocumentedInOpenApi() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/chat-rooms'].post.summary").value("초대 코드 채팅방 생성"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/join'].post.responses['404']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/participants/claim'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/participants/claim'].post.requestBody.content['multipart/form-data']").exists())
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/participants/claim'].post.requestBody.content['multipart/form-data'].schema['$ref']").value("#/components/schemas/ParticipantClaimRequest"))
				.andExpect(jsonPath("$.components.schemas.ParticipantClaimRequest.required", hasItem("nickname")))
				.andExpect(jsonPath("$.components.schemas.ParticipantClaimRequest.required", org.hamcrest.Matchers.not(hasItem("profileImage"))))
				.andExpect(jsonPath("$.components.schemas.ParticipantClaimRequest.properties.profileImage.type").value("string"))
				.andExpect(jsonPath("$.components.schemas.ParticipantClaimRequest.properties.profileImage.format").value("binary"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}'].get.summary").value("채팅방 조회"))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/messages'].post.requestBody.required")
						.value(true))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/messages'].get.parameters[*].name")
						.value(hasItem("beforeMessageId")))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/messages'].get.parameters[*].name")
						.value(hasItem("afterMessageId")))
				.andExpect(jsonPath("$.paths['/api/chat-rooms/{chatRoomId}/messages'].get.parameters[*].name")
						.value(hasItem("size")));
	}

	private TestContext createTestContext() {
		User userA = saveUser("사용자 A", "https://example.com/a.png");
		User userB = saveUser("사용자 B", "https://example.com/b.png");
		ChatRoom chatRoom = chatRoomRepository.save(new ChatRoom(
				userA,
				userB,
				LocalDate.of(2026, 8, 1),
				RoomStatus.ACTIVE,
				time(0),
				null
		));
		return new TestContext(userA, userB, chatRoom);
	}

	private ChatRoom createInviteRoom() {
		User user = saveUser(null, null);
		ChatRoom room = new ChatRoom(user, null, null, RoomStatus.ACTIVE, time(0), null);
		room.assignInviteCode("INVITE1");
		return chatRoomRepository.saveAndFlush(room);
	}

	private JoinAttempt tryJoin(String inviteCode, CountDownLatch ready, CountDownLatch start) {
		try {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent join did not start");
			chatService.joinChatRoom(inviteCode);
			return new JoinAttempt(200);
		} catch (org.springframework.web.server.ResponseStatusException exception) {
			return new JoinAttempt(exception.getStatusCode().value());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private User saveUser(String nickname, String profileImageUrl) {
		return userRepository.save(new User(null, null, nickname, profileImageUrl, time(0), time(0)));
	}

	private List<Message> saveMessages(TestContext context, int count) {
		List<Message> messages = new ArrayList<>();
		for (int index = 1; index <= count; index++) {
			messages.add(messageRepository.save(new Message(
					context.chatRoom(),
					context.userA(),
					"client-" + index,
					"메시지 " + index,
					time(index)
			)));
		}
		return messages;
	}

	private String messageRequest(String clientMessageId, String content, OffsetDateTime sentAt) {
		return """
				{
				  "clientMessageId": "%s",
				  "content": "%s",
				  "sentAt": "%s"
				}
				""".formatted(clientMessageId, content, sentAt);
	}

	private MockMultipartFile imageFile(byte[] content) {
		return new MockMultipartFile("profileImage", "image.png", MediaType.IMAGE_PNG_VALUE, content);
	}

	private ChatService claimServiceWhoseCommitFails(RuntimeException databaseFailure) {
		User user = mock(User.class);
		when(user.getId()).thenReturn(1L);
		when(user.getNickname()).thenReturn(null);
		ChatRoom room = mock(ChatRoom.class);
		when(room.getUserA()).thenReturn(user);
		when(room.getUserB()).thenReturn(null);
		UserRepository users = mock(UserRepository.class);
		when(users.findById(1L)).thenReturn(java.util.Optional.of(user));
		ChatRoomRepository rooms = mock(ChatRoomRepository.class);
		when(rooms.findWithUsersByIdForUpdate(1L)).thenReturn(java.util.Optional.of(room));
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		doThrow(databaseFailure).when(transactionManager).commit(any());
		return new ChatService(users, rooms, mock(MessageRepository.class), transactionManager,
				mock(ApplicationEventPublisher.class), profileImageStorage);
	}

	private OffsetDateTime time(int minute) {
		return OffsetDateTime.of(2026, 8, 18, 12, 0, 0, 0, ZoneOffset.ofHours(9)).plusMinutes(minute);
	}

	private record TestContext(User userA, User userB, ChatRoom chatRoom) {
	}

	private record JoinAttempt(int status) {
	}

	@TestConfiguration
	static class ProfileImageStorageTestConfig {
		@Bean
		@Primary
		FakeProfileImageStorage fakeProfileImageStorage() {
			return new FakeProfileImageStorage();
		}
	}

	static class FakeProfileImageStorage implements ProfileImageStorage {
		private int uploadCount;
		private int deleteCount;
		private String deletedPublicId;
		private boolean failUpload;
		private boolean failDelete;
		private boolean failMaxUpload;

		@Override
		public UploadedProfileImage upload(org.springframework.web.multipart.MultipartFile file) {
			uploadCount++;
			if (failMaxUpload) throw new org.springframework.web.multipart.MaxUploadSizeExceededException(5L * 1024 * 1024);
			if (failUpload) throw new ProfileImageStorageException("fake failure");
			return new UploadedProfileImage("profiles/image-" + uploadCount,
					"https://example.test/profiles/image-" + uploadCount);
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
			failMaxUpload = false;
		}
	}
}
