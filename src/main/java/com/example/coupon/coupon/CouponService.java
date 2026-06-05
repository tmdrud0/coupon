package com.example.coupon.coupon;

import com.example.coupon.common.CouponException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponStockSlotDao couponStockSlotDao;
	private final CouponRedisReservationService couponRedisReservationService;
	private final Clock clock;

	public CouponService(
			CouponRepository couponRepository,
			CouponIssueRepository couponIssueRepository,
			CouponStockSlotDao couponStockSlotDao,
			CouponRedisReservationService couponRedisReservationService,
			Clock clock
	) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
		this.couponStockSlotDao = couponStockSlotDao;
		this.couponRedisReservationService = couponRedisReservationService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<CouponSummaryResponse> findCoupons() {
		return couponRepository.findAll().stream()
				.map(coupon -> CouponSummaryResponse.from(coupon, couponIssueRepository.countByCouponId(coupon.getId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public CouponDetailResponse findCoupon(Long couponId) {
		Coupon coupon = getCoupon(couponId);
		long issuedCount = couponIssueRepository.countByCouponId(couponId);
		return CouponDetailResponse.from(coupon, issuedCount);
	}

	@Transactional
	public CouponIssueResponse issueCoupon(Long couponId, Long userId) {
		Coupon coupon = getCoupon(couponId);
		Instant now = Instant.now(clock);
		if (!coupon.canIssueAt(now)) {
			throw CouponException.conflict("NOT_STARTED", "Coupon issuing has not started.");
		}
		reserveCoupon(coupon, userId, false);
		RedisReservationCompensation compensation = registerRedisReservationCompensation(couponId, userId);

		try {
			Long slotId = couponStockSlotDao.lockAvailableSlot(couponId)
					.orElseThrow(() -> CouponException.conflict("SOLD_OUT", "Coupon is sold out."));
			couponStockSlotDao.markIssued(slotId, userId, now);
			CouponIssue issue = couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId, slotId, now));
			return CouponIssueResponse.from(issue, coupon);
		} catch (DataIntegrityViolationException exception) {
			compensation.compensateNow();
			throw CouponException.conflict("ALREADY_ISSUED", "User already received this coupon.");
		} catch (RuntimeException exception) {
			compensation.compensateNow();
			if (exception instanceof CouponException couponException && "SOLD_OUT".equals(couponException.code())) {
				couponRedisReservationService.rebuildRemaining(couponId, currentRemaining(coupon));
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public List<CouponIssueResponse> findUserIssues(Long userId) {
		List<CouponIssue> issues = couponIssueRepository.findByUserIdOrderByIssuedAtDesc(userId);
		Map<Long, Coupon> coupons = couponRepository.findAllById(
						issues.stream().map(CouponIssue::getCouponId).collect(Collectors.toSet())
				).stream()
				.collect(Collectors.toMap(Coupon::getId, Function.identity()));

		return issues.stream()
				.map(issue -> CouponIssueResponse.from(issue, coupons.get(issue.getCouponId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public CouponStatsResponse findStats(Long couponId) {
		Coupon coupon = getCoupon(couponId);
		long issuedCount = couponIssueRepository.countByCouponId(couponId);
		return CouponStatsResponse.from(coupon, issuedCount);
	}

	private Coupon getCoupon(Long couponId) {
		return couponRepository.findById(couponId)
				.orElseThrow(() -> CouponException.notFound("Coupon not found."));
	}

	private void reserveCoupon(Coupon coupon, Long userId, boolean retried) {
		Long couponId = coupon.getId();
		int initialRemaining = 0;
		if (!couponRedisReservationService.hasReservationState(couponId)) {
			initialRemaining = currentRemaining(coupon);
		}

		CouponRedisReservationService.ReservationResult reservationResult =
				couponRedisReservationService.reserve(couponId, userId, initialRemaining);
		if (reservationResult == CouponRedisReservationService.ReservationResult.RESERVED) {
			return;
		}
		if (reservationResult == CouponRedisReservationService.ReservationResult.ALREADY_ISSUED) {
			if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
				throw CouponException.conflict("ALREADY_ISSUED", "User already received this coupon.");
			}
			if (!retried) {
				couponRedisReservationService.compensate(couponId, userId);
				reserveCoupon(coupon, userId, true);
				return;
			}
			throw CouponException.conflict("SOLD_OUT", "Coupon is sold out.");
		}

		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw CouponException.conflict("ALREADY_ISSUED", "User already received this coupon.");
		}
		int currentRemaining = currentRemaining(coupon);
		if (currentRemaining > 0 && !retried) {
			couponRedisReservationService.rebuildRemaining(couponId, currentRemaining);
			reserveCoupon(coupon, userId, true);
			return;
		}
		throw CouponException.conflict("SOLD_OUT", "Coupon is sold out.");
	}

	private int currentRemaining(Coupon coupon) {
		return Math.max(0, Math.toIntExact(coupon.getTotalQuantity() - couponStockSlotDao.countIssuedSlots(coupon.getId())));
	}

	private RedisReservationCompensation registerRedisReservationCompensation(Long couponId, Long userId) {
		RedisReservationCompensation compensation = new RedisReservationCompensation(couponId, userId);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						compensation.compensateNow();
					}
				}
			});
		}
		return compensation;
	}

	private class RedisReservationCompensation {
		private final Long couponId;
		private final Long userId;
		private final AtomicBoolean compensated = new AtomicBoolean();

		private RedisReservationCompensation(Long couponId, Long userId) {
			this.couponId = couponId;
			this.userId = userId;
		}

		private void compensateNow() {
			if (compensated.compareAndSet(false, true)) {
				couponRedisReservationService.compensate(couponId, userId);
			}
		}
	}

	public record CouponSummaryResponse(
			Long id,
			String name,
			String description,
			Instant issueStartAt,
			int totalQuantity,
			long issuedCount,
			long remainingQuantity
	) {
		static CouponSummaryResponse from(Coupon coupon, long issuedCount) {
			return new CouponSummaryResponse(
					coupon.getId(),
					coupon.getName(),
					coupon.getDescription(),
					coupon.getIssueStartAt(),
					coupon.getTotalQuantity(),
					issuedCount,
					coupon.getTotalQuantity() - issuedCount
			);
		}
	}

	public record CouponDetailResponse(
			Long id,
			String name,
			String description,
			Instant issueStartAt,
			int totalQuantity,
			long issuedCount,
			long remainingQuantity
	) {
		static CouponDetailResponse from(Coupon coupon, long issuedCount) {
			return new CouponDetailResponse(
					coupon.getId(),
					coupon.getName(),
					coupon.getDescription(),
					coupon.getIssueStartAt(),
					coupon.getTotalQuantity(),
					issuedCount,
					coupon.getTotalQuantity() - issuedCount
			);
		}
	}

	public record CouponIssueResponse(
			Long issueId,
			Long couponId,
			String couponName,
			Long userId,
			Instant issuedAt
	) {
		static CouponIssueResponse from(CouponIssue issue, Coupon coupon) {
			return new CouponIssueResponse(
					issue.getId(),
					issue.getCouponId(),
					coupon == null ? null : coupon.getName(),
					issue.getUserId(),
					issue.getIssuedAt()
			);
		}
	}

	public record CouponStatsResponse(
			Long couponId,
			int totalQuantity,
			long issuedCount,
			long remainingQuantity
	) {
		static CouponStatsResponse from(Coupon coupon, long issuedCount) {
			return new CouponStatsResponse(
					coupon.getId(),
					coupon.getTotalQuantity(),
					issuedCount,
					coupon.getTotalQuantity() - issuedCount
			);
		}
	}
}
