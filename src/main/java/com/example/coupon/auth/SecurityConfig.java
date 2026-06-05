package com.example.coupon.auth;

import com.example.coupon.common.ApiErrorResponse;
import com.example.coupon.user.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			UserAccountRepository userAccountRepository,
			ObjectMapper objectMapper
	) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.addFilterBefore(
						new SessionAuthenticationFilter(userAccountRepository),
						UsernamePasswordAuthenticationFilter.class
				)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/login", "/actuator/health").permitAll()
						.anyRequest().authenticated()
				)
				.exceptionHandling(exception -> exception.authenticationEntryPoint((request, response, authException) -> {
					response.setStatus(HttpStatus.UNAUTHORIZED.value());
					response.setContentType(MediaType.APPLICATION_JSON_VALUE);
					objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of("UNAUTHORIZED", "Login is required."));
				}))
				.build();
	}
}
