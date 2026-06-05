package com.example.coupon.coupon;

import com.example.coupon.common.CouponException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponStockSlotDao couponStockSlotDao;
	private final Clock clock;

	public CouponService(
			CouponRepository couponRepository,
			CouponIssueRepository couponIssueRepository,
			CouponStockSlotDao couponStockSlotDao,
			Clock clock
	) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
		this.couponStockSlotDao = couponStockSlotDao;
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
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw CouponException.conflict("ALREADY_ISSUED", "User already received this coupon.");
		}

		Long slotId = couponStockSlotDao.lockAvailableSlot(couponId)
				.orElseThrow(() -> CouponException.conflict("SOLD_OUT", "Coupon is sold out."));
		couponStockSlotDao.markIssued(slotId, userId, now);

		try {
			CouponIssue issue = couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId, slotId, now));
			return CouponIssueResponse.from(issue, coupon);
		} catch (DataIntegrityViolationException exception) {
			throw CouponException.conflict("ALREADY_ISSUED", "User already received this coupon.");
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
