package com.example.coupon.asyncissue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxEventPublisher {

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private final CouponIssueKafkaPublisher kafkaPublisher;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final int batchSize;
	private final Duration claimTimeout;

	public OutboxEventPublisher(
			JdbcTemplate jdbcTemplate,
			TransactionTemplate transactionTemplate,
			CouponIssueKafkaPublisher kafkaPublisher,
			ObjectMapper objectMapper,
			Clock clock,
			@Value("${coupon.outbox.publisher.batch-size}") int batchSize,
			@Value("${coupon.outbox.publisher.claim-timeout}") Duration claimTimeout
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = transactionTemplate;
		this.kafkaPublisher = kafkaPublisher;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.batchSize = batchSize;
		this.claimTimeout = claimTimeout;
	}

	@Scheduled(
			fixedDelayString = "${coupon.outbox.publisher.fixed-delay}",
			initialDelayString = "${coupon.outbox.publisher.initial-delay}"
	)
	public void publishPending() {
		List<OutboxRow> rows = transactionTemplate.execute(status -> claimBatch());
		if (rows == null) {
			return;
		}
		for (OutboxRow row : rows) {
			publish(row);
		}
	}

	private List<OutboxRow> claimBatch() {
		Instant now = Instant.now(clock);
		Instant expiredBefore = now.minus(claimTimeout);
		String claimToken = UUID.randomUUID().toString();
		List<OutboxRow> claimedRows = jdbcTemplate.query("""
				SELECT id, payload
				FROM outbox_events
				WHERE status = 'PENDING'
				   OR (status = 'PROCESSING' AND claimed_at < ?)
				ORDER BY id
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""", (resultSet, rowNum) -> new OutboxRow(
				resultSet.getLong("id"),
				resultSet.getString("payload"),
				claimToken
		), Timestamp.from(expiredBefore), batchSize);
		for (OutboxRow row : claimedRows) {
			jdbcTemplate.update("""
					UPDATE outbox_events
					SET status = 'PROCESSING',
					    claimed_at = ?,
					    claim_token = ?,
					    attempts = attempts + 1
					WHERE id = ?
					""", Timestamp.from(now), claimToken, row.id());
		}
		return claimedRows;
	}

	private void publish(OutboxRow row) {
		try {
			kafkaPublisher.publish(deserialize(row.payload()));
			jdbcTemplate.update("""
					UPDATE outbox_events
					SET status = 'PUBLISHED',
					    published_at = ?,
					    claimed_at = NULL,
					    claim_token = NULL
					WHERE id = ?
					  AND status = 'PROCESSING'
					  AND claim_token = ?
					""", Timestamp.from(Instant.now(clock)), row.id(), row.claimToken());
		} catch (RuntimeException exception) {
			jdbcTemplate.update("""
					UPDATE outbox_events
					SET status = 'PENDING',
					    claimed_at = NULL,
					    claim_token = NULL
					WHERE id = ?
					  AND status = 'PROCESSING'
					  AND claim_token = ?
					""", row.id(), row.claimToken());
		}
	}

	private CouponIssueRequestedEvent deserialize(String payload) {
		try {
			return objectMapper.readValue(payload, CouponIssueRequestedEvent.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid coupon issue outbox event.", exception);
		}
	}

	private record OutboxRow(long id, String payload, String claimToken) {
	}
}
