package com.suspiciouslions.backend.domain.chat.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import com.suspiciouslions.backend.domain.chat.dto.ChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.MessageResponse;
import com.suspiciouslions.backend.domain.chat.dto.SendMessageRequest;
import com.suspiciouslions.backend.domain.chat.dto.CreateChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.JoinChatRoomRequest;
import com.suspiciouslions.backend.domain.chat.dto.JoinChatRoomResponse;
import com.suspiciouslions.backend.domain.chat.dto.ParticipantClaimResponse;
import com.suspiciouslions.backend.domain.chat.dto.ParticipantClaimRequest;
import com.suspiciouslions.backend.domain.chat.service.ChatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/chat-rooms")
public class ChatController {

	private static final String CHAT_ROOM_EXAMPLE = """
			{
			  "chatRoomId": 1,
			  "status": "ACTIVE",
			  "relationshipStartedOn": "2026-08-01",
			  "partner": {
			    "userId": 2,
			    "nickname": "카카포",
			    "profileImageUrl": "https://example.com/profile.png"
			  }
			}
			""";

	private static final String MESSAGE_EXAMPLE = """
			{
			  "messageId": 101,
			  "chatRoomId": 1,
			  "senderId": 1,
			  "clientMessageId": "client-message-uuid",
			  "content": "메시지 내용",
			  "sentAt": "2026-08-18T16:00:00+09:00"
			}
			""";

	private final ChatService chatService;

	public ChatController(ChatService chatService) {
		this.chatService = chatService;
	}

	@Operation(summary = "초대 코드 채팅방 생성", description = "첫 임시 참가자와 6자리 초대 코드를 생성합니다.", tags = "Chat Rooms")
	@ApiResponses(@ApiResponse(responseCode = "200", description = "생성 성공"))
	@PostMapping
	public CreateChatRoomResponse createChatRoom() {
		return chatService.createChatRoom();
	}

	@Operation(summary = "초대 코드로 채팅방 입장", description = "두 번째 임시 참가자를 등록합니다.", tags = "Chat Rooms")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "입장 성공"),
			@ApiResponse(responseCode = "404", description = "초대 코드를 찾을 수 없음", content = @Content),
			@ApiResponse(responseCode = "409", description = "이미 참가자가 두 명인 방", content = @Content)
	})
	@PostMapping(path = "/join", consumes = MediaType.APPLICATION_JSON_VALUE)
	public JoinChatRoomResponse joinChatRoom(@Valid @RequestBody JoinChatRoomRequest request) {
		return chatService.joinChatRoom(request.inviteCode());
	}

	@Operation(summary = "참가자 닉네임 및 프로필 이미지 등록",
			description = "nickname은 필수이고 profileImage는 선택입니다. 이미지는 image/*, 최대 5MB이며 Cloudinary profiles 폴더에 저장됩니다.",
			tags = "Chat Rooms",
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
							schema = @Schema(implementation = ParticipantClaimRequest.class))))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "등록 성공"),
			@ApiResponse(responseCode = "403", description = "다른 방의 사용자", content = @Content),
			@ApiResponse(responseCode = "404", description = "사용자 또는 채팅방을 찾을 수 없음", content = @Content),
			@ApiResponse(responseCode = "409", description = "이미 등록했거나 두 참가자 모두 등록됨", content = @Content),
			@ApiResponse(responseCode = "502", description = "프로필 이미지 외부 업로드 실패", content = @Content)
	})
	@PostMapping(path = "/{chatRoomId}/participants/claim", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ParticipantClaimResponse claimNickname(
			@PathVariable @Positive Long chatRoomId,
			@RequestHeader("X-User-Id") @Positive Long userId,
			@Parameter(hidden = true) @RequestParam @NotBlank String nickname,
			@Parameter(hidden = true)
		@RequestParam(required = false) MultipartFile profileImage) {
		return chatService.claimNickname(chatRoomId, userId, nickname, profileImage);
	}

	@Operation(summary = "채팅방 조회", description = "참여 중인 채팅방과 상대방 정보를 조회합니다.", tags = "Chat Rooms")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "채팅방 조회 성공",
					content = @Content(schema = @Schema(implementation = ChatRoomResponse.class),
							examples = @ExampleObject(value = CHAT_ROOM_EXAMPLE))),
			@ApiResponse(responseCode = "400", description = "Path 또는 X-User-Id 형식 오류", content = @Content),
			@ApiResponse(responseCode = "403", description = "요청 사용자가 채팅방 참여자가 아님", content = @Content),
			@ApiResponse(responseCode = "404", description = "사용자 또는 채팅방을 찾을 수 없음", content = @Content)
	})
	@GetMapping("/{chatRoomId}")
	public ChatRoomResponse getChatRoom(
			@Parameter(description = "채팅방 ID", required = true, example = "1")
			@PathVariable @Positive Long chatRoomId,
			@Parameter(name = "X-User-Id", description = "임시 사용자 ID", required = true,
					example = "1", in = ParameterIn.HEADER)
			@RequestHeader("X-User-Id") @Positive Long userId) {
		return chatService.getChatRoom(chatRoomId, userId);
	}

	@Operation(summary = "메시지 전송", description = "텍스트 메시지를 멱등하게 저장하고 반환합니다.", tags = "Messages")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "메시지 저장 또는 기존 메시지 반환 성공",
					content = @Content(schema = @Schema(implementation = MessageResponse.class),
							examples = @ExampleObject(value = MESSAGE_EXAMPLE))),
			@ApiResponse(responseCode = "400", description = "필수값 누락, 빈 메시지 또는 요청 형식 오류", content = @Content),
			@ApiResponse(responseCode = "403", description = "요청 사용자가 채팅방 참여자가 아님", content = @Content),
			@ApiResponse(responseCode = "404", description = "사용자 또는 채팅방을 찾을 수 없음", content = @Content),
			@ApiResponse(responseCode = "409", description = "같은 사용자의 clientMessageId가 다른 채팅방에서 사용됨",
					content = @Content)
	})
	@PostMapping(path = "/{chatRoomId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
	public MessageResponse sendMessage(
			@Parameter(description = "채팅방 ID", required = true, example = "1")
			@PathVariable @Positive Long chatRoomId,
			@Parameter(name = "X-User-Id", description = "임시 사용자 ID", required = true,
					example = "1", in = ParameterIn.HEADER)
			@RequestHeader("X-User-Id") @Positive Long userId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "전송할 메시지. 모든 필드는 필수입니다.", required = true,
					content = @Content(schema = @Schema(implementation = SendMessageRequest.class),
							examples = @ExampleObject(value = """
								{
								  "clientMessageId": "client-message-uuid",
								  "content": "메시지 내용",
								  "sentAt": "2026-08-18T16:00:00+09:00"
								}
								""")))
			@Valid @RequestBody SendMessageRequest request) {
		return chatService.sendMessage(chatRoomId, userId, request);
	}

	@Operation(summary = "메시지 조회", description = "ID 커서 기준으로 메시지를 과거에서 최신 순으로 조회합니다.",
			tags = "Messages")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "메시지 목록 조회 성공",
					content = @Content(array = @ArraySchema(schema = @Schema(implementation = MessageResponse.class)),
							examples = @ExampleObject(value = "[" + MESSAGE_EXAMPLE + "]"))),
			@ApiResponse(responseCode = "400",
					description = "beforeMessageId와 afterMessageId 동시 사용, size 범위 또는 파라미터 형식 오류",
					content = @Content),
			@ApiResponse(responseCode = "403", description = "요청 사용자가 채팅방 참여자가 아님", content = @Content),
			@ApiResponse(responseCode = "404", description = "사용자 또는 채팅방을 찾을 수 없음", content = @Content)
	})
	@GetMapping("/{chatRoomId}/messages")
	public List<MessageResponse> getMessages(
			@Parameter(description = "채팅방 ID", required = true, example = "1")
			@PathVariable @Positive Long chatRoomId,
			@Parameter(name = "X-User-Id", description = "임시 사용자 ID", required = true,
					example = "1", in = ParameterIn.HEADER)
			@RequestHeader("X-User-Id") @Positive Long userId,
			@Parameter(description = "선택값. 해당 ID보다 과거 메시지 조회", example = "101")
			@RequestParam(required = false) @Positive Long beforeMessageId,
			@Parameter(description = "선택값. 해당 ID보다 신규 메시지 조회", example = "101")
			@RequestParam(required = false) @Positive Long afterMessageId,
			@Parameter(description = "선택값. 조회 개수(기본 30, 최소 1, 최대 100)", example = "30")
			@RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
		if (beforeMessageId != null && afterMessageId != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"beforeMessageId and afterMessageId cannot be used together");
		}
		return chatService.getMessages(chatRoomId, userId, beforeMessageId, afterMessageId, size);
	}
}
