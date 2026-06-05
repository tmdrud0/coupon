package com.example.coupon.coupon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "coupons")
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 500)
	private String description;

	@Column(nullable = false)
	private Instant issueStartAt;

	@Column(nullable = false)
	private int totalQuantity;

	@Column(nullable = false)
	private Instant createdAt;

	protected Coupon() {
	}

	public Coupon(String name, String description, Instant issueStartAt, int totalQuantity, Instant createdAt) {
		this.name = name;
		this.description = description;
		this.issueStartAt = issueStartAt;
		this.totalQuantity = totalQuantity;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public Instant getIssueStartAt() {
		return issueStartAt;
	}

	public int getTotalQuantity() {
		return totalQuantity;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean canIssueAt(Instant now) {
		return !now.isBefore(issueStartAt);
	}
}
