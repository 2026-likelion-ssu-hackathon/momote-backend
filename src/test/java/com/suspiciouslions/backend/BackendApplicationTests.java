package com.suspiciouslions.backend;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.suspiciouslions.backend.domain.ai.entity.AiResult;
import com.suspiciouslions.backend.domain.ai.entity.AiResultType;
import com.suspiciouslions.backend.domain.ai.entity.ContentType;
import com.suspiciouslions.backend.domain.ai.entity.VisibilityType;
import com.suspiciouslions.backend.domain.ai.repository.AiResultRepository;
import com.suspiciouslions.backend.domain.chat.entity.ChatRoom;
import com.suspiciouslions.backend.domain.chat.entity.Message;
import com.suspiciouslions.backend.domain.chat.entity.RoomStatus;
import com.suspiciouslions.backend.domain.chat.repository.ChatRoomRepository;
import com.suspiciouslions.backend.domain.chat.repository.MessageRepository;
import com.suspiciouslions.backend.domain.emotion.entity.EmotionAnalysis;
import com.suspiciouslions.backend.domain.emotion.entity.EmotionType;
import com.suspiciouslions.backend.domain.emotion.repository.EmotionAnalysisRepository;
import com.suspiciouslions.backend.domain.user.entity.User;
import com.suspiciouslions.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:3000")
@AutoConfigureMockMvc
@Testcontainers
class BackendApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private MessageRepository messageRepository;

	@Autowired
	private AiResultRepository aiResultRepository;

	@Autowired
	private EmotionAnalysisRepository emotionAnalysisRepository;

	@Test
	void contextLoads() {
	}

	@Test
	void flywayCreatesCoreEmotionAndInviteCodeSchema() {
		List<String> migrations = jdbcTemplate.queryForList("""
				SELECT version
				FROM flyway_schema_history
				WHERE success = true AND version IS NOT NULL
				ORDER BY installed_rank
				""", String.class);

		List<String> tables = jdbcTemplate.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN (
				      'users', 'chat_rooms', 'messages', 'ai_results',
				      'emotion_analyses', 'chat_room_join_requests', 'flyway_schema_history'
				  )
				ORDER BY table_name
				""", String.class);

		assertEquals(List.of("1", "2", "3", "4"), migrations);
		assertEquals(List.of(
				"ai_results",
				"chat_room_join_requests",
				"chat_rooms",
				"emotion_analyses",
				"flyway_schema_history",
				"messages",
				"users"
		), tables);

		List<String> nullableColumns = jdbcTemplate.queryForList("""
				SELECT table_name || '.' || column_name
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND (table_name = 'users' AND column_name = 'nickname' OR table_name = 'chat_rooms' AND column_name = 'user_b_id')
				  AND is_nullable = 'YES'
				ORDER BY table_name, column_name
				""", String.class);
		List<String> inviteCodeColumns = jdbcTemplate.queryForList("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'chat_rooms' AND column_name = 'invite_code'
				""", String.class);
		List<String> inviteCodeUniqueConstraints = jdbcTemplate.queryForList("""
				SELECT tc.constraint_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				WHERE tc.table_schema = 'public'
				  AND tc.table_name = 'chat_rooms'
				  AND tc.constraint_type = 'UNIQUE'
				  AND kcu.column_name = 'invite_code'
				""", String.class);

		assertEquals(List.of("chat_rooms.user_b_id", "users.nickname"), nullableColumns);
		assertEquals(List.of("invite_code"), inviteCodeColumns);
		assertEquals(List.of("uq_chat_rooms_invite_code"), inviteCodeUniqueConstraints);
	}

	@Test
	void aiResultPayloadColumnsUseJsonb() {
		List<String> jsonbColumns = jdbcTemplate.queryForList("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name IN ('ai_results', 'emotion_analyses')
				  AND data_type = 'jsonb'
				ORDER BY table_name, column_name
				""", String.class);

		assertEquals(List.of("result_data", "trigger_message_ids", "trigger_message_ids"), jsonbColumns);
	}

	@Test
	void dataSourceUsesOnlyTheTestcontainer() throws SQLException {
		try (var connection = dataSource.getConnection()) {
			assertTrue(postgres.isRunning());
			assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL());
		}
	}

	@Test
	@Transactional
	void repositoriesSaveAndLoadUsersChatRoomAndMessage() {
		TestContext testContext = createTestContext();
		OffsetDateTime sentAt = time(10);
		Message message = messageRepository.save(new Message(
				testContext.chatRoom(),
				testContext.userA(),
				"client-message-1",
				"테스트 메시지",
				sentAt
		));

		entityManager.flush();
		entityManager.clear();

		User foundUser = userRepository.findById(testContext.userA().getId()).orElseThrow();
		ChatRoom foundChatRoom = chatRoomRepository.findById(testContext.chatRoom().getId()).orElseThrow();
		Message foundMessage = messageRepository.findById(message.getId()).orElseThrow();

		assertEquals("사용자 A", foundUser.getNickname());
		assertEquals(RoomStatus.ACTIVE, foundChatRoom.getRoomStatus());
		assertEquals("테스트 메시지", foundMessage.getContent());
		assertEquals(testContext.userA().getId(), foundMessage.getSender().getId());
	}

	@Test
	@Transactional
	void aiResultJsonbSavesAndLoads() {
		TestContext testContext = createTestContext();
		AiResult saved = aiResultRepository.save(new AiResult(
				UUID.randomUUID(),
				testContext.chatRoom(),
				testContext.userA(),
				AiResultType.TONE_CORRECTION,
				VisibilityType.INDIVIDUAL,
				ContentType.TEXT,
				List.of(101L, 105L),
				Map.of("guideMessage", "대신 이렇게 말해보세요."),
				time(20)
		));

		entityManager.flush();
		entityManager.clear();

		AiResult found = aiResultRepository.findById(saved.getId()).orElseThrow();
		assertEquals(List.of(101L, 105L), found.getTriggerMessageIds());
		assertEquals("대신 이렇게 말해보세요.", found.getResultData().get("guideMessage"));
		assertEquals(VisibilityType.INDIVIDUAL, found.getVisibilityType());
	}

	@Test
	@Transactional
	void emotionAnalysisJsonbAndStateTextSaveAndLoad() {
		TestContext testContext = createTestContext();
		EmotionAnalysis saved = emotionAnalysisRepository.save(new EmotionAnalysis(
				UUID.randomUUID(),
				testContext.chatRoom(),
				testContext.userA(),
				testContext.userB(),
				EmotionType.ESCALATED,
				new BigDecimal("3.0"),
				false,
				List.of(206L, 208L),
				"감정이 올라와요",
				time(30),
				time(33)
		));

		entityManager.flush();
		entityManager.clear();

		EmotionAnalysis found = emotionAnalysisRepository.findById(saved.getId()).orElseThrow();
		assertEquals(List.of(206L, 208L), found.getTriggerMessageIds());
		assertEquals("감정이 올라와요", found.getStateText());
		assertEquals(EmotionType.ESCALATED, found.getEmotionType());
		assertEquals(0, new BigDecimal("3.0").compareTo(found.getIntensityValue()));
		assertFalse(found.isShouldShow());
	}

	@Test
	@Transactional
	void allEmotionTypesCanBePersisted() {
		TestContext testContext = createTestContext();
		for (EmotionType emotionType : EmotionType.values()) {
			emotionAnalysisRepository.save(new EmotionAnalysis(
					UUID.randomUUID(),
					testContext.chatRoom(),
					testContext.userA(),
					testContext.userB(),
					emotionType,
					new BigDecimal("2.5"),
					true,
					List.of(),
					null,
					time(40),
					time(43)
			));
		}

		entityManager.flush();

		assertEquals(
				List.of("STABLE", "RESOLVED", "ACCUMULATED", "ENGAGED", "ESCALATED"),
				Arrays.stream(EmotionType.values()).map(Enum::name).toList()
		);
		assertEquals(5, emotionAnalysisRepository.count());
	}

	@Test
	@Transactional
	void emotionIntensityOutsideRangeIsRejectedByDatabase() {
		TestContext testContext = createTestContext();
		EmotionAnalysis invalid = new EmotionAnalysis(
				UUID.randomUUID(),
				testContext.chatRoom(),
				testContext.userA(),
				testContext.userB(),
				EmotionType.STABLE,
				new BigDecimal("5.1"),
				true,
				List.of(),
				null,
				time(50),
				time(53)
		);

		assertThrows(DataIntegrityViolationException.class,
				() -> emotionAnalysisRepository.saveAndFlush(invalid));
	}

	@Test
	@Transactional
	void sameSubjectAndViewerIsRejectedByDatabase() {
		TestContext testContext = createTestContext();
		EmotionAnalysis invalid = new EmotionAnalysis(
				UUID.randomUUID(),
				testContext.chatRoom(),
				testContext.userA(),
				testContext.userA(),
				EmotionType.STABLE,
				BigDecimal.ZERO,
				true,
				List.of(),
				null,
				time(60),
				time(63)
		);

		assertThrows(DataIntegrityViolationException.class,
				() -> emotionAnalysisRepository.saveAndFlush(invalid));
	}

	@Test
	void healthCheckReturnsUp() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void openApiDocsAreAvailable() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists())
				.andExpect(jsonPath("$.info.title").value("Wellness Mate API"));
	}

	@Test
	void corsAllowsConfiguredOrigin() throws Exception {
		mockMvc.perform(options("/api/health")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
	}

	private TestContext createTestContext() {
		OffsetDateTime now = time(0);
		User userA = userRepository.save(new User(null, null, "사용자 A", null, now, now));
		User userB = userRepository.save(new User(null, null, "사용자 B", null, now, now));
		ChatRoom chatRoom = chatRoomRepository.save(new ChatRoom(
				userA,
				userB,
				LocalDate.of(2026, 8, 1),
				RoomStatus.ACTIVE,
				now,
				null
		));

		assertNotNull(userA.getId());
		assertNotNull(userB.getId());
		assertNotNull(chatRoom.getId());
		return new TestContext(userA, userB, chatRoom);
	}

	private OffsetDateTime time(int minute) {
		return OffsetDateTime.of(2026, 8, 18, 12, 0, 0, 0, ZoneOffset.ofHours(9))
				.plusMinutes(minute);
	}

	private record TestContext(User userA, User userB, ChatRoom chatRoom) {
	}

}
