package com.example.coupon.auth;

import com.example.coupon.user.UserAccount;
import com.example.coupon.user.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class SessionAuthenticationFilter extends OncePerRequestFilter {

	public static final String USER_ID_SESSION_KEY = "LOGIN_USER_ID";

	private final UserAccountRepository userAccountRepository;

	public SessionAuthenticationFilter(UserAccountRepository userAccountRepository) {
		this.userAccountRepository = userAccountRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			Object userId = session.getAttribute(USER_ID_SESSION_KEY);
			if (userId instanceof Long id) {
				userAccountRepository.findById(id).ifPresent(this::authenticate);
			}
		}
		filterChain.doFilter(request, response);
	}

	private void authenticate(UserAccount user) {
		CouponPrincipal principal = new CouponPrincipal(user.getId(), user.getUsername());
		UsernamePasswordAuthenticationToken authentication =
				new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
