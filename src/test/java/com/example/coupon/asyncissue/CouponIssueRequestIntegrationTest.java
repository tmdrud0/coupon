package com.example.coupon.asyncissue;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.coupon.Coupon;
import com.example.coupon.coupon.CouponIssueRepository;
import com.example.coupon.coupon.CouponRepository;
import com.example.coupon.coupon.CouponStockSlotDao;
import com.example.coupon.user.UserAccount;
import com.example.coupon.user.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"coupon.outbox.publisher.initial-delay=600000",
		"coupon.outbox.publisher.fixed-delay=600000"
})
@AutoConfigureMockMvc
class CouponIssueRequestIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	CouponIssueRequestService requestService;

	@Autowired
	CouponIssueRequestRepository requestRepository;

	@Autowired
	CouponIssueConsumer consumer;

	@Autowired
	OutboxEventPublisher outboxEventPublisher;

	@Autowired
	CouponRepository couponRepository;

	@Autowired
	CouponIssueRepository couponIssueRepository;

	@Autowired
	CouponStockSlotDao couponStockSlotDao;

	@Autowired
	UserAccountRepository userAccountRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	@MockitoSpyBean
	CouponIssueRequestStore requestStore;

	@MockitoSpyBean
	CouponIssueKafkaPublisher kafkaPublisher;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM outbox_events");
		jdbcTemplate.update("DELETE FROM coupon_issue_requests");
		jdbcTemplate.update("DELETE FROM coupon_issues");
		jdbcTemplate.update("DELETE FROM coupon_stock_slots");
		jdbcTemplate.update("DELETE FROM coupons");
		jdbcTemplate.update("DELETE FROM users");
	}

	@AfterEach
	void resetSpies() {
		reset(requestStore, kafkaPublisher);
	}

	@Test
	void submitBothModesAndReturnAcceptedPending() throws Exception {
		Coupon coupon = createCoupon("Submit", 2);
		MockHttpSession directUser = login("direct-user");
		MockHttpSession outboxUser = login("outbox-user");

		mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/kafka", coupon.getId())
						.session(directUser))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.requestId").isNumber())
				.andExpect(jsonPath("$.mode").value("DIRECT_KAFKA"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.resultCode").doesNotExist());

		mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/outbox", coupon.getId())
						.session(outboxUser))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.requestId").isNumber())
				.andExpect(jsonPath("$.mode").value("OUTBOX"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.resultCode").doesNotExist());

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_requests", Long.class))
				.isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
				.isEqualTo(1);
	}

	@Test
	void statusLookupIsLimitedToOwningUser() throws Exception {
		Coupon coupon = createCoupon("Owned", 1);
		MockHttpSession owner = login("owner");
		MockHttpSession stranger = login("stranger");
		long requestId = submitOutbox(coupon.getId(), owner).path("requestId").asLong();

		mockMvc.perform(get("/api/coupon-issue-requests/{requestId}", requestId).session(owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestId").value(requestId))
				.andExpect(jsonPath("$.status").value("PENDING"));

		mockMvc.perform(get("/api/coupon-issue-requests/{requestId}", requestId).session(stranger))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void duplicateSubmissionReturnsSameRequest() throws Exception {
		Coupon coupon = createCoupon("Duplicate", 1);
		MockHttpSession session = login("same-user");

		long firstId = submitOutbox(coupon.getId(), session).path("requestId").asLong();
		long secondId = submitOutbox(coupon.getId(), session).path("requestId").asLong();

		assertThat(secondId).isEqualTo(firstId);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_requests", Long.class))
				.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
				.isEqualTo(1);
	}

	@Test
	void directKafkaRequestIsFinallyIssued() throws Exception {
		Coupon coupon = createCoupon("Direct", 1);
		MockHttpSession session = login("direct-success");
		long requestId = submitDirect(coupon.getId(), session).path("requestId").asLong();

		await(() -> requestRepository.findById(requestId)
				.map(request -> request.getStatus() == CouponIssueRequestStatus.ISSUED)
				.orElse(false));

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void directKafkaPublishFailureLeavesSharedRequestPending() throws Exception {
		Coupon coupon = createCoupon("Direct Failure", 1);
		MockHttpSession session = login("direct-failure");
		doThrow(new CouponIssueKafkaPublisher.CouponIssuePublishException(
				"ambiguous publish timeout",
				new RuntimeException("timeout")
		)).when(kafkaPublisher).publish(any());

		mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/kafka", coupon.getId())
						.session(session))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("KAFKA_UNAVAILABLE"));

		CouponIssueRequest request = requestRepository.findByCouponIdAndUserIdAndMode(
				coupon.getId(),
				userAccountRepository.findByUsername("direct-failure").orElseThrow().getId(),
				CouponIssueRequestMode.DIRECT_KAFKA
		).orElseThrow();
		assertThat(request.getStatus()).isEqualTo(CouponIssueRequestStatus.PENDING);
		assertThat(request.getResultCode()).isNull();
	}

	@Test
	void outboxRequestsPreserveCouponOrderAndRejectSoldOut() {
		Coupon coupon = createCoupon("One Left", 1);
		UserAccount first = createUser("first");
		UserAccount second = createUser("second");
		long firstRequestId = requestService.submitOutbox(coupon.getId(), first.getId()).requestId();
		long secondRequestId = requestService.submitOutbox(coupon.getId(), second.getId()).requestId();

		outboxEventPublisher.publishPending();

		await(() -> isFinal(firstRequestId) && isFinal(secondRequestId));
		CouponIssueRequest firstRequest = requestRepository.findById(firstRequestId).orElseThrow();
		CouponIssueRequest secondRequest = requestRepository.findById(secondRequestId).orElseThrow();

		assertThat(firstRequest.getStatus()).isEqualTo(CouponIssueRequestStatus.ISSUED);
		assertThat(secondRequest.getStatus()).isEqualTo(CouponIssueRequestStatus.REJECTED);
		assertThat(secondRequest.getResultCode()).isEqualTo(CouponIssueResultCode.SOLD_OUT);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void duplicateKafkaDeliveryDoesNotCreateAnotherIssue() {
		Coupon coupon = createCoupon("Idempotent", 2);
		UserAccount user = createUser("delivery-user");
		long requestId = requestService.submitOutbox(coupon.getId(), user.getId()).requestId();
		CouponIssueRequestedEvent event = new CouponIssueRequestedEvent(
				requestId,
				coupon.getId(),
				user.getId(),
				CouponIssueRequestMode.OUTBOX
		);

		consumer.process(event);
		consumer.process(event);

		assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
				.isEqualTo(CouponIssueRequestStatus.ISSUED);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(1);
	}

	@Test
	void duplicateIssueInsertDoesNotConsumeAnotherAvailableSlot() {
		Coupon coupon = createCoupon("Race", 2);
		UserAccount user = createUser("race-user");
		Instant now = Instant.now();

		transactionTemplate.executeWithoutResult(status -> {
			Long firstSlotId = couponStockSlotDao.lockAvailableSlot(coupon.getId()).orElseThrow();
			assertThat(couponStockSlotDao.createIssueIfAbsent(coupon.getId(), user.getId(), firstSlotId, now)).isTrue();
			couponStockSlotDao.markIssued(firstSlotId, user.getId(), now);
		});

		transactionTemplate.executeWithoutResult(status -> {
			Long secondSlotId = couponStockSlotDao.lockAvailableSlot(coupon.getId()).orElseThrow();
			boolean inserted = couponStockSlotDao.createIssueIfAbsent(
					coupon.getId(),
					user.getId(),
					secondSlotId,
					now.plusSeconds(1)
			);
			assertThat(inserted).isFalse();
			if (inserted) {
				couponStockSlotDao.markIssued(secondSlotId, user.getId(), now.plusSeconds(1));
			}
		});

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(1);
	}

	@Test
	void staleOutboxPublisherCannotFinalizeNewerClaim() {
		Coupon coupon = createCoupon("Claim Ownership", 1);
		UserAccount user = createUser("claim-user");
		requestService.submitOutbox(coupon.getId(), user.getId());
		String replacementToken = UUID.randomUUID().toString();

		doAnswer(invocation -> {
			Long eventId = jdbcTemplate.queryForObject(
					"SELECT id FROM outbox_events WHERE request_id = ?",
					Long.class,
					requestRepository.findByCouponIdAndUserIdAndMode(
							coupon.getId(),
							user.getId(),
							CouponIssueRequestMode.OUTBOX
					).orElseThrow().getId()
			);
			jdbcTemplate.update("""
					UPDATE outbox_events
					SET claim_token = ?, claimed_at = ?
					WHERE id = ? AND status = 'PROCESSING'
					""", replacementToken, Timestamp.from(Instant.now()), eventId);
			return null;
		}).when(kafkaPublisher).publish(any());

		outboxEventPublisher.publishPending();

		assertThat(jdbcTemplate.queryForMap("""
				SELECT status, claim_token, published_at
				FROM outbox_events
				"""))
				.containsEntry("status", "PROCESSING")
				.containsEntry("claim_token", replacementToken)
				.containsEntry("published_at", null);
	}

	@Test
	void outboxInsertFailureRollsBackRequest() {
		Coupon coupon = createCoupon("Atomic", 1);
		UserAccount user = createUser("atomic-user");
		doThrow(new IllegalStateException("forced outbox failure"))
				.when(requestStore).createOutboxEvent(any(), any());

		assertThatThrownBy(() -> requestService.submitOutbox(coupon.getId(), user.getId()))
				.isInstanceOf(RuntimeException.class);

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_requests", Long.class))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
				.isZero();
	}

	@Test
	void rejectNotStartedAtSubmissionWithoutPersistingRequest() throws Exception {
		Coupon coupon = createCoupon("Future", Instant.parse("2099-01-01T00:00:00Z"), 1);
		MockHttpSession session = login("early-user");

		mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/outbox", coupon.getId())
						.session(session))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NOT_STARTED"));

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_requests", Long.class))
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class))
				.isZero();
	}

	private JsonNode submitDirect(Long couponId, MockHttpSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/kafka", couponId)
						.session(session))
				.andExpect(status().isAccepted())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private JsonNode submitOutbox(Long couponId, MockHttpSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/coupons/{couponId}/issue-requests/outbox", couponId)
						.session(session))
				.andExpect(status().isAccepted())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsByteArray());
	}

	private MockHttpSession login(String username) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(username))))
				.andExpect(status().isOk())
				.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private Coupon createCoupon(String name, int quantity) {
		return createCoupon(name, Instant.parse("2020-01-01T00:00:00Z"), quantity);
	}

	private Coupon createCoupon(String name, Instant issueStartAt, int quantity) {
		Coupon coupon = couponRepository.saveAndFlush(new Coupon(
				name,
				name + " coupon",
				issueStartAt,
				quantity,
				Instant.now()
		));
		couponStockSlotDao.createSlots(coupon.getId(), quantity, Instant.now());
		return coupon;
	}

	private UserAccount createUser(String username) {
		return userAccountRepository.saveAndFlush(new UserAccount(username, Instant.now()));
	}

	private boolean isFinal(long requestId) {
		return requestRepository.findById(requestId)
				.map(request -> request.getStatus() != CouponIssueRequestStatus.PENDING)
				.orElse(false);
	}

	private long countSlots(Long couponId, String status) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = ? AND status = ?",
				Long.class,
				couponId,
				status
		);
	}

	private void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for asynchronous processing.", exception);
			}
		}
		throw new AssertionError("Timed out waiting for asynchronous processing.");
	}

	private record LoginRequest(String username) {
	}
}
