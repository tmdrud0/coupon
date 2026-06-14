package com.example.coupon.asyncissue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class CouponIssueRequestStore {

	private final JdbcTemplate jdbcTemplate;
	private final CouponIssueRequestRepository requestRepository;
	private final ObjectMapper objectMapper;

	public CouponIssueRequestStore(
			JdbcTemplate jdbcTemplate,
			CouponIssueRequestRepository requestRepository,
			ObjectMapper objectMapper
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.requestRepository = requestRepository;
		this.objectMapper = objectMapper;
	}

	public CreatedRequest create(Long couponId, Long userId, CouponIssueRequestMode mode, Instant now) {
		int inserted = jdbcTemplate.update("""
				INSERT IGNORE INTO coupon_issue_requests
				    (coupon_id, user_id, mode, status, result_code, created_at, updated_at)
				VALUES (?, ?, ?, 'PENDING', NULL, ?, ?)
				""", couponId, userId, mode.name(), Timestamp.from(now), Timestamp.from(now));
		CouponIssueRequest request = requestRepository.findByCouponIdAndUserIdAndMode(couponId, userId, mode)
				.orElseThrow(() -> new IllegalStateException("Coupon issue request was not persisted."));
		return new CreatedRequest(request, inserted == 1);
	}

	public void createOutboxEvent(CouponIssueRequest request, Instant now) {
		CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(
				request.getId(),
				request.getCouponId(),
				request.getUserId(),
				request.getMode()
		);
		jdbcTemplate.update("""
				INSERT INTO outbox_events
				    (request_id, event_type, payload, status, attempts, created_at)
				VALUES (?, 'COUPON_ISSUE_REQUESTED', ?, 'PENDING', 0, ?)
				""", request.getId(), serialize(event), Timestamp.from(now));
	}

	private String serialize(CouponIssueRequestedEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize coupon issue event.", exception);
		}
	}

	public record CreatedRequest(CouponIssueRequest request, boolean created) {
	}
}
