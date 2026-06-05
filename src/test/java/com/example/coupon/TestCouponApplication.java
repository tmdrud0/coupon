package com.example.coupon;

import org.springframework.boot.SpringApplication;

public class TestCouponApplication {

	public static void main(String[] args) {
		SpringApplication.from(CouponApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
