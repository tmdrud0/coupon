package com.example.coupon.auth;

import com.example.coupon.TestcontainersConfiguration;
import com.example.coupon.coupon.Coupon;
import com.example.coupon.coupon.CouponRepository;
import com.example.coupon.coupon.CouponStockSlotDao;
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

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	CouponRepository couponRepository;

	@Autowired
	CouponStockSlotDao couponStockSlotDao;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Long couponId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM coupon_issues");
		jdbcTemplate.update("DELETE FROM coupon_stock_slots");
		jdbcTemplate.update("DELETE FROM coupons");
		jdbcTemplate.update("DELETE FROM users");

		Coupon coupon = couponRepository.saveAndFlush(new Coupon(
				"API Coupon",
				"API test coupon",
				Instant.parse("2020-01-01T00:00:00Z"),
				1,
				Instant.now()
		));
		couponStockSlotDao.createSlots(coupon.getId(), 1, Instant.now());
		couponId = coupon.getId();
	}

	@Test
	void rejectUnauthenticatedRequest() throws Exception {
		mockMvc.perform(get("/api/coupons"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void loginAndIssueCouponThroughApi() throws Exception {
		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"api-user\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("api-user"))
				.andReturn();

		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

		mockMvc.perform(post("/api/coupons/{couponId}/issues", couponId)
						.session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.couponId").value(couponId))
				.andExpect(jsonPath("$.couponName").value("API Coupon"));

		mockMvc.perform(post("/api/coupons/{couponId}/issues", couponId)
						.session(session))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_ISSUED"));
	}
}
