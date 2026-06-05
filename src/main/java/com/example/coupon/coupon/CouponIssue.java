package com.example.coupon.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coupon_issues")
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long couponId;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private Long stockSlotId;

	@Column(nullable = false)
	private Instant issuedAt;

	protected CouponIssue() {
	}

	public CouponIssue(Long couponId, Long userId, Long stockSlotId, Instant issuedAt) {
		this.couponId = couponId;
		this.userId = userId;
		this.stockSlotId = stockSlotId;
		this.issuedAt = issuedAt;
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

	public Long getStockSlotId() {
		return stockSlotId;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}
}
