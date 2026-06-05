package com.example.coupon.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByCouponIdAndUserId(Long couponId, Long userId);

	long countByCouponId(Long couponId);

	List<CouponIssue> findByUserIdOrderByIssuedAtDesc(Long userId);
}
