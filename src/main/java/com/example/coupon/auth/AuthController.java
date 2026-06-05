package com.example.coupon.auth;

import com.example.coupon.user.UserAccount;
import com.example.coupon.user.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserAccountRepository userAccountRepository;

	public AuthController(UserAccountRepository userAccountRepository) {
		this.userAccountRepository = userAccountRepository;
	}

	@PostMapping("/login")
	@Transactional
	LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
		UserAccount user = userAccountRepository.findByUsername(request.username())
				.orElseGet(() -> userAccountRepository.save(new UserAccount(request.username(), Instant.now())));
		httpRequest.getSession(true).setAttribute(SessionAuthenticationFilter.USER_ID_SESSION_KEY, user.getId());
		return new LoginResponse(user.getId(), user.getUsername());
	}

	public record LoginRequest(
			@NotBlank
			@Size(max = 100)
			String username
	) {
	}

	public record LoginResponse(Long userId, String username) {
	}
}
