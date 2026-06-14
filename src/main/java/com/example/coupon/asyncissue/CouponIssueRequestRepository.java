package com.example.coupon.asyncissue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CouponIssueRequestRepository extends JpaRepository<CouponIssueRequest, Long> {

	Optional<CouponIssueRequest> findByCouponIdAndUserIdAndMode(
			Long couponId,
			Long userId,
			CouponIssueRequestMode mode
	);

	Optional<CouponIssueRequest> findByIdAndUserId(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from CouponIssueRequest request where request.id = :id")
	Optional<CouponIssueRequest> findByIdForUpdate(@Param("id") Long id);
}
