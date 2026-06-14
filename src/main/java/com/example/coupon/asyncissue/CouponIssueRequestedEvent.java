package com.example.coupon.asyncissue;

public record CouponIssueRequestedEvent(
		Long requestId,
		Long couponId,
		Long userId,
		CouponIssueRequestMode mode
) {
}
