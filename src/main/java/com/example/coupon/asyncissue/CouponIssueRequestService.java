package com.example.coupon.asyncissue;

import com.example.coupon.common.CouponException;
import com.example.coupon.coupon.Coupon;
import com.example.coupon.coupon.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

@Service
public class CouponIssueRequestService {

	private final CouponRepository couponRepository;
	private final CouponIssueRequestRepository requestRepository;
	private final CouponIssueRequestStore requestStore;
	private final CouponIssueKafkaPublisher kafkaPublisher;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public CouponIssueRequestService(
			CouponRepository couponRepository,
			CouponIssueRequestRepository requestRepository,
			CouponIssueRequestStore requestStore,
			CouponIssueKafkaPublisher kafkaPublisher,
			TransactionTemplate transactionTemplate,
			Clock clock
	) {
		this.couponRepository = couponRepository;
		this.requestRepository = requestRepository;
		this.requestStore = requestStore;
		this.kafkaPublisher = kafkaPublisher;
		this.transactionTemplate = transactionTemplate;
		this.clock = clock;
	}

	public CouponIssueRequestResponse submitDirect(Long couponId, Long userId) {
		CouponIssueRequestStore.CreatedRequest created = transactionTemplate.execute(status ->
				createRequest(couponId, userId, CouponIssueRequestMode.DIRECT_KAFKA, false)
		);
		if (created == null) {
			throw new IllegalStateException("Coupon issue request transaction returned no result.");
		}
		CouponIssueRequest request = created.request();
		if (request.getStatus() != CouponIssueRequestStatus.PENDING) {
			return CouponIssueRequestResponse.from(request);
		}

		try {
			kafkaPublisher.publish(toEvent(request));
		} catch (CouponIssueKafkaPublisher.CouponIssuePublishException exception) {
			throw CouponException.serviceUnavailable(
					"KAFKA_UNAVAILABLE",
					"Kafka acknowledgement was not confirmed. The request remains pending and may be retried."
			);
		}
		return CouponIssueRequestResponse.pending(request);
	}

	public CouponIssueRequestResponse submitOutbox(Long couponId, Long userId) {
		CouponIssueRequestStore.CreatedRequest created = transactionTemplate.execute(status ->
				createRequest(couponId, userId, CouponIssueRequestMode.OUTBOX, true)
		);
		if (created == null) {
			throw new IllegalStateException("Coupon issue request transaction returned no result.");
		}
		return created.request().getStatus() == CouponIssueRequestStatus.PENDING
				? CouponIssueRequestResponse.pending(created.request())
				: CouponIssueRequestResponse.from(created.request());
	}

	public CouponIssueRequestResponse findOwned(Long requestId, Long userId) {
		return transactionTemplate.execute(status -> requestRepository.findByIdAndUserId(requestId, userId)
				.map(CouponIssueRequestResponse::from)
				.orElseThrow(() -> CouponException.notFound("Coupon issue request not found.")));
	}

	private CouponIssueRequestStore.CreatedRequest createRequest(
			Long couponId,
			Long userId,
			CouponIssueRequestMode mode,
			boolean withOutbox
	) {
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> CouponException.notFound("Coupon not found."));
		Instant now = Instant.now(clock);
		if (!coupon.canIssueAt(now)) {
			throw CouponException.conflict("NOT_STARTED", "Coupon issuing has not started.");
		}
		CouponIssueRequestStore.CreatedRequest created = requestStore.create(couponId, userId, mode, now);
		if (withOutbox && created.created()) {
			requestStore.createOutboxEvent(created.request(), now);
		}
		return created;
	}

	private CouponIssueRequestedEvent toEvent(CouponIssueRequest request) {
		return new CouponIssueRequestedEvent(
				request.getId(),
				request.getCouponId(),
				request.getUserId(),
				request.getMode()
		);
	}

	public record CouponIssueRequestResponse(
			Long requestId,
			CouponIssueRequestMode mode,
			CouponIssueRequestStatus status,
			CouponIssueResultCode resultCode
	) {
		static CouponIssueRequestResponse from(CouponIssueRequest request) {
			return new CouponIssueRequestResponse(
					request.getId(),
					request.getMode(),
					request.getStatus(),
					request.getResultCode()
			);
		}

		static CouponIssueRequestResponse pending(CouponIssueRequest request) {
			return new CouponIssueRequestResponse(
					request.getId(),
					request.getMode(),
					CouponIssueRequestStatus.PENDING,
					null
			);
		}
	}
}
