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
	UserAccountRepository userAccountRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
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

		assertThat(response.couponId()).isEqualTo(coupon.getId());
		assertThat(response.userId()).isEqualTo(user.getId());
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
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
		Coupon coupon = createCoupon("Open", Instant.parse("2020-01-01T00:00:00Z"), 2);

		couponService.issueCoupon(coupon.getId(), user.getId());

		assertThatThrownBy(() -> couponService.issueCoupon(coupon.getId(), user.getId()))
				.isInstanceOf(CouponException.class)
				.extracting("code")
				.isEqualTo("ALREADY_ISSUED");
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
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
}
