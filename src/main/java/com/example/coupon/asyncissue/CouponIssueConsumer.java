package com.example.coupon.asyncissue;

import com.example.coupon.coupon.CouponIssueRepository;
import com.example.coupon.coupon.CouponRepository;
import com.example.coupon.coupon.CouponStockSlotDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Component
public class CouponIssueConsumer {

	private final ObjectMapper objectMapper;
	private final CouponIssueRequestRepository requestRepository;
	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponStockSlotDao couponStockSlotDao;
	private final Clock clock;

	public CouponIssueConsumer(
			ObjectMapper objectMapper,
			CouponIssueRequestRepository requestRepository,
			CouponRepository couponRepository,
			CouponIssueRepository couponIssueRepository,
			CouponStockSlotDao couponStockSlotDao,
			Clock clock
	) {
		this.objectMapper = objectMapper;
		this.requestRepository = requestRepository;
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
		this.couponStockSlotDao = couponStockSlotDao;
		this.clock = clock;
	}

	@KafkaListener(topics = "${coupon.kafka.issue-topic}")
	@Transactional
	public void consume(String payload) {
		process(deserialize(payload));
	}

	@Transactional
	public void process(CouponIssueRequestedEvent event) {
		CouponIssueRequest request = requestRepository.findByIdForUpdate(event.requestId()).orElse(null);
		if (request == null || request.getStatus() != CouponIssueRequestStatus.PENDING) {
			return;
		}
		if (!request.getCouponId().equals(event.couponId())
				|| !request.getUserId().equals(event.userId())
				|| request.getMode() != event.mode()) {
			throw new IllegalArgumentException("Kafka event does not match the persisted coupon issue request.");
		}

		Instant now = Instant.now(clock);
		if (couponRepository.findById(request.getCouponId())
				.map(coupon -> coupon.canIssueAt(now))
				.orElse(false) == false) {
			request.markRejected(CouponIssueResultCode.NOT_STARTED, now);
			return;
		}
		if (couponIssueRepository.existsByCouponIdAndUserId(request.getCouponId(), request.getUserId())) {
			request.markRejected(CouponIssueResultCode.ALREADY_ISSUED, now);
			return;
		}

		Long slotId = couponStockSlotDao.lockAvailableSlot(request.getCouponId()).orElse(null);
		if (slotId == null) {
			request.markRejected(CouponIssueResultCode.SOLD_OUT, now);
			return;
		}
		if (!couponStockSlotDao.createIssueIfAbsent(
				request.getCouponId(),
				request.getUserId(),
				slotId,
				now
		)) {
			request.markRejected(CouponIssueResultCode.ALREADY_ISSUED, now);
			return;
		}
		couponStockSlotDao.markIssued(slotId, request.getUserId(), now);
		request.markIssued(now);
	}

	private CouponIssueRequestedEvent deserialize(String payload) {
		try {
			return objectMapper.readValue(payload, CouponIssueRequestedEvent.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid coupon issue Kafka event.", exception);
		}
	}
}
