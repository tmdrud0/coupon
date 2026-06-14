package com.example.coupon.coupon;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.common.CouponException;
import com.example.coupon.user.UserAccount;
import com.example.coupon.user.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CouponIssueIntegrationTest {

	@Autowired
	CouponService couponService;

	@Autowired
	CouponRepository couponRepository;

	@Autowired
	CouponIssueRepository couponIssueRepository;

	@Autowired
	CouponStockSlotDao couponStockSlotDao;

	@Autowired
	CouponRedisReservationService couponRedisReservationService;

	@Autowired
	UserAccountRepository userAccountRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.queryForList("SELECT id FROM coupons", Long.class)
				.forEach(couponRedisReservationService::resetCoupon);
		jdbcTemplate.update("DELETE FROM outbox_events");
		jdbcTemplate.update("DELETE FROM coupon_issue_requests");
		jdbcTemplate.update("DELETE FROM coupon_issues");
		jdbcTemplate.update("DELETE FROM coupon_stock_slots");
		jdbcTemplate.update("DELETE FROM coupons");
		jdbcTemplate.update("DELETE FROM users");
	}

	@Test
	void issueCoupon() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		CouponService.CouponIssueResponse response = couponService.issueCoupon(coupon.getId(), user.getId());

		assertThat(response.issueId()).isNotNull();
		assertThat(response.couponId()).isEqualTo(coupon.getId());
		assertThat(response.couponName()).isEqualTo(coupon.getName());
		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(response.issuedAt()).isNotNull();
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void failMissingCoupon() {
		UserAccount user = createUser("user-1");

		assertThatThrownBy(() -> couponService.issueCoupon(999999L, user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("NOT_FOUND");
	}

	@Test
	void failBeforeStartTime() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Future", Instant.parse("2099-01-01T00:00:00Z"), 1);

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("NOT_STARTED");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isZero();
	}

	@Test
	void failDuplicateIssue() {
		UserAccount user = createUser("user-1");
		UserAccount second = createUser("user-2");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 2);

		couponService.issueCoupon(coupon.getId(), user.getId());

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("ALREADY_ISSUED");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(1);

		couponService.issueCoupon(coupon.getId(), second.getId());

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(2);
	}

	@Test
	void failSoldOut() {
		UserAccount first = createUser("user-1");
		UserAccount second = createUser("user-2");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		couponService.issueCoupon(coupon.getId(), first.getId());

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), second.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("SOLD_OUT");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void failDuplicateAfterSoldOutAsAlreadyIssued() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		couponService.issueCoupon(coupon.getId(), user.getId());

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("ALREADY_ISSUED");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void failDuplicateAfterRedisStateIsRebuiltAsAlreadyIssued() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		couponService.issueCoupon(coupon.getId(), user.getId());
		couponRedisReservationService.resetCoupon(coupon.getId());

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("ALREADY_ISSUED");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
	}

	@Test
	void recoverStaleRedisAlreadyIssuedReservationWhenMysqlHasNoIssue() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		assertThat(couponRedisReservationService.reserve(coupon.getId(), user.getId(), 1))
				.isEqualTo(CouponRedisReservationService.ReservationResult.RESERVED);

		CouponService.CouponIssueResponse response = couponService.issueCoupon(coupon.getId(), user.getId());

		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
	}

	@Test
	void recoverStaleRedisSoldOutReservationWhenMysqlStillHasStock() {
		UserAccount staleUser = createUser("user-1");
		UserAccount actualUser = createUser("user-2");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		assertThat(couponRedisReservationService.reserve(coupon.getId(), staleUser.getId(), 1))
				.isEqualTo(CouponRedisReservationService.ReservationResult.RESERVED);

		CouponService.CouponIssueResponse response = couponService.issueCoupon(coupon.getId(), actualUser.getId());

		assertThat(response.userId()).isEqualTo(actualUser.getId());
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
	}

	@Test
	void rebuildRedisRemainingWhenStaleReservationRetriesAfterMysqlSoldOut() {
		UserAccount staleUser = createUser("user-1");
		UserAccount issuedUser = createUser("user-2");
		UserAccount nextUser = createUser("user-3");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);
		Instant now = Instant.parse("2020-01-01T00:00:01Z");

		assertThat(couponRedisReservationService.reserve(coupon.getId(), staleUser.getId(), 1))
				.isEqualTo(CouponRedisReservationService.ReservationResult.RESERVED);
		transactionTemplate.executeWithoutResult(status -> {
			Long slotId = couponStockSlotDao.lockAvailableSlot(coupon.getId()).orElseThrow();
			couponStockSlotDao.markIssued(slotId, issuedUser.getId(), now);
			insertIssue(coupon.getId(), issuedUser.getId(), slotId, now);
		});

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), staleUser.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("SOLD_OUT");

		assertThat(couponRedisReservationService.reserve(coupon.getId(), nextUser.getId(), 0))
				.isEqualTo(CouponRedisReservationService.ReservationResult.SOLD_OUT);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
	}

	@Test
	void rollBackStockSlotUpdateWhenIssueInsertFailsByDuplicateKey() {
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 2);
		Instant now = Instant.parse("2020-01-01T00:00:01Z");

		couponService.issueCoupon(coupon.getId(), user.getId());

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
					Long slotId = couponStockSlotDao.lockAvailableSlot(coupon.getId()).orElseThrow();
					couponStockSlotDao.markIssued(slotId, user.getId(), now);
					insertIssue(coupon.getId(), user.getId(), slotId, now);
				}));

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(1);
	}

	@Test
	void compensateRedisReservationWhenDatabaseDuplicateGuardRejectsIssue() {
		UserAccount first = createUser("user-1");
		UserAccount second = createUser("user-2");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 2);
		Instant now = Instant.parse("2020-01-01T00:00:01Z");

		transactionTemplate.executeWithoutResult(status -> {
			Long slotId = couponStockSlotDao.lockAvailableSlot(coupon.getId()).orElseThrow();
			couponStockSlotDao.markIssued(slotId, first.getId(), now);
			insertIssue(coupon.getId(), first.getId(), slotId, now);
		});

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), first.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("ALREADY_ISSUED");

		couponService.issueCoupon(coupon.getId(), second.getId());

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(2);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(2);
	}

	@Test
	void compensateRedisReservationWhenOuterTransactionRollsBackAfterIssueReturns() {
		UserAccount first = createUser("user-1");
		UserAccount second = createUser("user-2");
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 1);

		transactionTemplate.executeWithoutResult(status -> {
			couponService.issueCoupon(coupon.getId(), first.getId());
			status.setRollbackOnly();
		});

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isZero();
		assertThat(countSlots(coupon.getId(), "ISSUED")).isZero();
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(1);

		couponService.issueCoupon(coupon.getId(), second.getId());

		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isZero();
	}

	@Test
	void issueExactlyTotalQuantityUnderConcurrentRequests() throws Exception {
		int totalQuantity = 20;
		int requestCount = 100;
		Coupon coupon = createCoupon("Hot", Instant.parse("2020-01-01T00:00:00Z"), totalQuantity);
		List<UserAccount> users = new ArrayList<>();
		for (int i = 0; i < requestCount; i++) {
			users.add(createUser("user-" + i));
		}

		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger soldOuts = new AtomicInteger();
		var executor = Executors.newFixedThreadPool(32);
		try {
			List<Callable<Void>> tasks = users.stream()
					.<Callable<Void>>map(user -> () -> {
						start.await(5, TimeUnit.SECONDS);
						try {
							couponService.issueCoupon(coupon.getId(), user.getId());
							successes.incrementAndGet();
						} catch (CouponException exception) {
							if ("SOLD_OUT".equals(exception.code())) {
								soldOuts.incrementAndGet();
							} else {
								throw exception;
							}
						}
						return null;
					})
					.toList();

			List<Future<Void>> futures = tasks.stream()
					.map(executor::submit)
					.toList();
			start.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
			for (Future<Void> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(successes.get()).isEqualTo(totalQuantity);
		assertThat(soldOuts.get()).isEqualTo(requestCount - totalQuantity);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(totalQuantity);
		Long distinctUsers = jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT user_id) FROM coupon_issues WHERE coupon_id = ?",
				Long.class,
				coupon.getId()
		);
		assertThat(distinctUsers).isEqualTo(totalQuantity);
	}

	@Test
	void concurrentRequestsFromSameUserCreateOneIssueAndDoNotLoseStock() throws Exception {
		int totalQuantity = 5;
		int requestCount = 12;
		UserAccount user = createUser("user-1");
		Coupon coupon = createCoupon("Hot", Instant.parse("2020-01-01T00:00:00Z"), totalQuantity);

		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger alreadyIssued = new AtomicInteger();
		AtomicInteger soldOuts = new AtomicInteger();
		var executor = Executors.newFixedThreadPool(requestCount);
		try {
			List<Callable<Void>> tasks = new ArrayList<>();
			for (int i = 0; i < requestCount; i++) {
				tasks.add(() -> {
					start.await(5, TimeUnit.SECONDS);
					try {
						couponService.issueCoupon(coupon.getId(), user.getId());
						successes.incrementAndGet();
					} catch (CouponException exception) {
						if ("ALREADY_ISSUED".equals(exception.code())) {
							alreadyIssued.incrementAndGet();
						} else if ("SOLD_OUT".equals(exception.code())) {
							soldOuts.incrementAndGet();
						} else {
							throw exception;
						}
					}
					return null;
				});
			}

			List<Future<Void>> futures = tasks.stream()
					.map(executor::submit)
					.toList();
			start.countDown();
			executor.shutdown();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
			for (Future<Void> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(successes.get()).isEqualTo(1);
		assertThat(alreadyIssued.get() + soldOuts.get()).isEqualTo(requestCount - 1);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "ISSUED")).isEqualTo(1);
		assertThat(countSlots(coupon.getId(), "AVAILABLE")).isEqualTo(totalQuantity - 1);
	}

	private UserAccount createUser(String username) {
		return userAccountRepository.saveAndFlush(new UserAccount(username, Instant.now()));
	}

	private Coupon createCoupon(String name, Instant issueStartAt, int totalQuantity) {
		Coupon coupon = couponRepository.saveAndFlush(new Coupon(
				name,
				name + " coupon",
				issueStartAt,
				totalQuantity,
				Instant.now()
		));
		couponStockSlotDao.createSlots(coupon.getId(), totalQuantity, Instant.now());
		return coupon;
	}

	private Long countSlots(Long couponId, String status) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = ? AND status = ?",
				Long.class,
				couponId,
				status
		);
	}

	private void insertIssue(Long couponId, Long userId, Long stockSlotId, Instant issuedAt) {
		jdbcTemplate.update("""
				INSERT INTO coupon_issues (coupon_id, user_id, stock_slot_id, issued_at)
				VALUES (?, ?, ?, ?)
				""", couponId, userId, stockSlotId, Timestamp.from(issuedAt));
	}
}
