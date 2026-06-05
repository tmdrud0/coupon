package com.example.coupon.config;

import com.example.coupon.coupon.Coupon;
import com.example.coupon.coupon.CouponRepository;
import com.example.coupon.coupon.CouponStockSlotDao;
import com.example.coupon.user.UserAccount;
import com.example.coupon.user.UserAccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class SampleDataInitializer implements ApplicationRunner {

	private final UserAccountRepository userAccountRepository;
	private final CouponRepository couponRepository;
	private final CouponStockSlotDao couponStockSlotDao;

	public SampleDataInitializer(
			UserAccountRepository userAccountRepository,
			CouponRepository couponRepository,
			CouponStockSlotDao couponStockSlotDao
	) {
		this.userAccountRepository = userAccountRepository;
		this.couponRepository = couponRepository;
		this.couponStockSlotDao = couponStockSlotDao;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (couponRepository.count() > 0) {
			return;
		}

		Instant now = Instant.now();
		if (!userAccountRepository.existsByUsername("alice")) {
			userAccountRepository.save(new UserAccount("alice", now));
		}
		if (!userAccountRepository.existsByUsername("bob")) {
			userAccountRepository.save(new UserAccount("bob", now));
		}

		Coupon welcome = couponRepository.save(new Coupon(
				"Welcome Coupon",
				"Open coupon for local testing.",
				Instant.parse("2020-01-01T00:00:00Z"),
				100,
				now
		));
		couponStockSlotDao.createSlots(welcome.getId(), welcome.getTotalQuantity(), now);

		Coupon future = couponRepository.save(new Coupon(
				"Future Coupon",
				"Coupon that has not started yet.",
				Instant.parse("2099-01-01T00:00:00Z"),
				10,
				now
		));
		couponStockSlotDao.createSlots(future.getId(), future.getTotalQuantity(), now);
	}
}
