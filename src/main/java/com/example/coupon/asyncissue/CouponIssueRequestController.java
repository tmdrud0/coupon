package com.example.coupon.asyncissue;

import com.example.coupon.auth.CouponPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CouponIssueRequestController {

	private final CouponIssueRequestService requestService;

	public CouponIssueRequestController(CouponIssueRequestService requestService) {
		this.requestService = requestService;
	}

	@PostMapping("/coupons/{couponId}/issue-requests/kafka")
	ResponseEntity<CouponIssueRequestService.CouponIssueRequestResponse> submitKafka(
			@PathVariable Long couponId,
			@AuthenticationPrincipal CouponPrincipal principal
	) {
		return ResponseEntity.accepted().body(requestService.submitDirect(couponId, principal.userId()));
	}

	@PostMapping("/coupons/{couponId}/issue-requests/outbox")
	ResponseEntity<CouponIssueRequestService.CouponIssueRequestResponse> submitOutbox(
			@PathVariable Long couponId,
			@AuthenticationPrincipal CouponPrincipal principal
	) {
		return ResponseEntity.accepted().body(requestService.submitOutbox(couponId, principal.userId()));
	}

	@GetMapping("/coupon-issue-requests/{requestId}")
	CouponIssueRequestService.CouponIssueRequestResponse findRequest(
			@PathVariable Long requestId,
			@AuthenticationPrincipal CouponPrincipal principal
	) {
		return requestService.findOwned(requestId, principal.userId());
	}
}
