package com.example.coupon.coupon;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class CouponStockSlotDao {

	private final JdbcTemplate jdbcTemplate;

	public CouponStockSlotDao(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<Long> lockAvailableSlot(Long couponId) {
		try {
			Long slotId = jdbcTemplate.queryForObject("""
					SELECT id
					FROM coupon_stock_slots
					WHERE coupon_id = ? AND status = 'AVAILABLE'
					ORDER BY id
					LIMIT 1
					FOR UPDATE SKIP LOCKED
					""", Long.class, couponId);
			return Optional.ofNullable(slotId);
		} catch (EmptyResultDataAccessException exception) {
			return Optional.empty();
		}
	}

	public void markIssued(Long slotId, Long userId, Instant issuedAt) {
		int updated = jdbcTemplate.update("""
				UPDATE coupon_stock_slots
				SET status = 'ISSUED',
				    issued_to_user_id = ?,
				    issued_at = ?
				WHERE id = ? AND status = 'AVAILABLE'
				""", userId, Timestamp.from(issuedAt), slotId);
		if (updated != 1) {
			throw new IllegalStateException("Locked coupon stock slot was not updated.");
		}
	}

	public void createSlots(Long couponId, int quantity, Instant createdAt) {
		for (int i = 0; i < quantity; i++) {
			jdbcTemplate.update("""
					INSERT INTO coupon_stock_slots (coupon_id, status, created_at)
					VALUES (?, 'AVAILABLE', ?)
					""", couponId, Timestamp.from(createdAt));
		}
	}

	public long countIssuedSlots(Long couponId) {
		Long count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM coupon_stock_slots
				WHERE coupon_id = ? AND status = 'ISSUED'
				""", Long.class, couponId);
		return count == null ? 0 : count;
	}
}
