package com.suspiciouslions.backend.domain.chat.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ChatMultipartExceptionHandler {

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Void> handleMaxUploadSizeExceeded() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
	}
}
