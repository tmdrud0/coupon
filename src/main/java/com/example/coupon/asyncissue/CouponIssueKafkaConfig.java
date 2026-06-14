package com.example.coupon.asyncissue;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableKafka
@EnableScheduling
public class CouponIssueKafkaConfig {

	@Bean
	NewTopic couponIssueRequestsTopic(@Value("${coupon.kafka.issue-topic}") String topic) {
		return TopicBuilder.name(topic)
				.partitions(6)
				.replicas(1)
				.build();
	}
}
