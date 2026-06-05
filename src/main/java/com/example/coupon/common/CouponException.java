package com.example.coupon.common;

import org.springframework.http.HttpStatus;

public class CouponException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	public CouponException(String code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}

	public static CouponException notFound(String message) {
		return new CouponException("NOT_FOUND", HttpStatus.NOT_FOUND, message);
	}

	public static CouponException unauthorized() {
		return new CouponException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Login is required.");
	}

	public static CouponException conflict(String code, String message) {
		return new CouponException(code, HttpStatus.CONFLICT, message);
	}
}
