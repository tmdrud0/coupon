package com.example.coupon.coupon;

import com.example.coupon.auth.CouponPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CouponController {

	private final CouponService couponService;

	public CouponController(CouponService couponService) {
		this.couponService = couponService;
	}

	@GetMapping("/coupons")
	List<CouponService.CouponSummaryResponse> findCoupons() {
		return couponService.findCoupons();
	}

	@GetMapping("/coupons/{couponId}")
	CouponService.CouponDetailResponse findCoupon(@PathVariable Long couponId) {
		return couponService.findCoupon(couponId);
	}

	@PostMapping("/coupons/{couponId}/issues")
	CouponService.CouponIssueResponse issueCoupon(
			@PathVariable Long couponId,
			@AuthenticationPrincipal CouponPrincipal principal
	) {
		return couponService.issueCoupon(couponId, principal.userId());
	}

	@GetMapping("/me/coupon-issues")
	List<CouponService.CouponIssueResponse> findMyIssues(@AuthenticationPrincipal CouponPrincipal principal) {
		return couponService.findUserIssues(principal.userId());
	}

	@GetMapping("/coupons/{couponId}/stats")
	CouponService.CouponStatsResponse findStats(@PathVariable Long couponId) {
		return couponService.findStats(couponId);
	}
}
