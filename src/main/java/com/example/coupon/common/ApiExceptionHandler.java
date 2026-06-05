package com.example.coupon.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(CouponException.class)
	ResponseEntity<ApiErrorResponse> handleCouponException(CouponException exception) {
		return ResponseEntity.status(exception.status())
				.body(ApiErrorResponse.of(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		String message = exception.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(ApiExceptionHandler::formatFieldError)
				.orElse("Invalid request.");
		return ResponseEntity.badRequest()
				.body(ApiErrorResponse.of("VALIDATION_FAILED", message));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiErrorResponse.of("INTERNAL_ERROR", "Unexpected server error."));
	}

	private static String formatFieldError(FieldError error) {
		return error.getField() + " " + error.getDefaultMessage();
	}
}
