package com.example.coupon.coupon;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class CouponRedisReservationService {

	private static final String RESERVED = "RESERVED";
	private static final String ALREADY_ISSUED = "ALREADY_ISSUED";
	private static final String SOLD_OUT = "SOLD_OUT";

	private static final DefaultRedisScript<String> RESERVE_SCRIPT = new DefaultRedisScript<>("""
			local stockKey = KEYS[1]
			local usersKey = KEYS[2]
			local userId = ARGV[1]
			local initialRemaining = tonumber(ARGV[2])
			local ttlSeconds = tonumber(ARGV[3])
			local initialized = 0

			local function refreshTtl()
				if ttlSeconds > 0 then
					redis.call('EXPIRE', stockKey, ttlSeconds)
					if redis.call('EXISTS', usersKey) == 1 then
						redis.call('EXPIRE', usersKey, ttlSeconds)
					end
				end
			end

			if redis.call('SISMEMBER', usersKey, userId) == 1 then
				return 'ALREADY_ISSUED'
			end

			if redis.call('EXISTS', stockKey) == 0 then
				redis.call('SET', stockKey, initialRemaining)
				initialized = 1
			end

			local remaining = tonumber(redis.call('GET', stockKey))
			if remaining <= 0 then
				if initialized == 1 then
					refreshTtl()
				end
				return 'SOLD_OUT'
			end

			redis.call('SADD', usersKey, userId)
			redis.call('DECR', stockKey)
			refreshTtl()
			return 'RESERVED'
			""", String.class);

	private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>("""
			local stockKey = KEYS[1]
			local usersKey = KEYS[2]
			local userId = ARGV[1]
			local ttlSeconds = tonumber(ARGV[2])

			if redis.call('SREM', usersKey, userId) == 1 then
				local remaining = redis.call('INCR', stockKey)
				if ttlSeconds > 0 then
					redis.call('EXPIRE', stockKey, ttlSeconds)
					if redis.call('EXISTS', usersKey) == 1 then
						redis.call('EXPIRE', usersKey, ttlSeconds)
					end
				end
				return remaining
			end

			return -1
			""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final long reservationTtlSeconds;

	public CouponRedisReservationService(
			StringRedisTemplate redisTemplate,
			@Value("${coupon.redis.reservation-ttl:PT24H}") Duration reservationTtl
	) {
		this.redisTemplate = redisTemplate;
		this.reservationTtlSeconds = Math.max(reservationTtl.getSeconds(), 0);
	}

	public ReservationResult reserve(Long couponId, Long userId, int initialRemaining) {
		String result = redisTemplate.execute(
				RESERVE_SCRIPT,
				List.of(stockKey(couponId), usersKey(couponId)),
				userId.toString(),
				Integer.toString(Math.max(initialRemaining, 0)),
				Long.toString(reservationTtlSeconds)
		);
		return ReservationResult.valueOf(result);
	}

	public boolean hasReservationState(Long couponId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(stockKey(couponId)));
	}

	public void compensate(Long couponId, Long userId) {
		redisTemplate.execute(
				COMPENSATE_SCRIPT,
				List.of(stockKey(couponId), usersKey(couponId)),
				userId.toString(),
				Long.toString(reservationTtlSeconds)
		);
	}

	public void rebuildRemaining(Long couponId, int remaining) {
		redisTemplate.opsForValue().set(stockKey(couponId), Integer.toString(Math.max(remaining, 0)));
		if (reservationTtlSeconds > 0) {
			redisTemplate.expire(stockKey(couponId), Duration.ofSeconds(reservationTtlSeconds));
			redisTemplate.expire(usersKey(couponId), Duration.ofSeconds(reservationTtlSeconds));
		}
	}

	public void resetCoupon(Long couponId) {
		redisTemplate.delete(List.of(stockKey(couponId), usersKey(couponId)));
	}

	private String stockKey(Long couponId) {
		return "coupon:%d:stock:remaining".formatted(couponId);
	}

	private String usersKey(Long couponId) {
		return "coupon:%d:users".formatted(couponId);
	}

	public enum ReservationResult {
		RESERVED,
		ALREADY_ISSUED,
		SOLD_OUT
	}
}
