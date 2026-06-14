package com.example.coupon.asyncissue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class CouponIssueKafkaPublisher {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final String topic;
	private final Duration publishTimeout;

	public CouponIssueKafkaPublisher(
			KafkaTemplate<String, String> kafkaTemplate,
			ObjectMapper objectMapper,
			@Value("${coupon.kafka.issue-topic}") String topic,
			@Value("${coupon.kafka.publish-timeout}") Duration publishTimeout
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.topic = topic;
		this.publishTimeout = publishTimeout;
	}

	public void publish(CouponIssueRequestedEvent event) {
		try {
			String payload = objectMapper.writeValueAsString(event);
			kafkaTemplate.send(topic, event.couponId().toString(), payload)
					.get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize coupon issue event.", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new CouponIssuePublishException("Kafka did not acknowledge the coupon issue request.", exception);
		} catch (Exception exception) {
			throw new CouponIssuePublishException("Kafka did not acknowledge the coupon issue request.", exception);
		}
	}

	public static class CouponIssuePublishException extends RuntimeException {
		public CouponIssuePublishException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
