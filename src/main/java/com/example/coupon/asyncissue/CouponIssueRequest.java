package com.example.coupon.asyncissue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coupon_issue_requests")
public class CouponIssueRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long couponId;

	@Column(nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CouponIssueRequestMode mode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CouponIssueRequestStatus status;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private CouponIssueResultCode resultCode;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected CouponIssueRequest() {
	}

	public Long getId() {
		return id;
	}

	public Long getCouponId() {
		return couponId;
	}

	public Long getUserId() {
		return userId;
	}

	public CouponIssueRequestMode getMode() {
		return mode;
	}

	public CouponIssueRequestStatus getStatus() {
		return status;
	}

	public CouponIssueResultCode getResultCode() {
		return resultCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void markIssued(Instant now) {
		status = CouponIssueRequestStatus.ISSUED;
		resultCode = null;
		updatedAt = now;
	}

	public void markRejected(CouponIssueResultCode code, Instant now) {
		status = CouponIssueRequestStatus.REJECTED;
		resultCode = code;
		updatedAt = now;
	}
}
