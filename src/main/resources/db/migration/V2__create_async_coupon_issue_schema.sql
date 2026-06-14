CREATE TABLE coupon_issue_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_code VARCHAR(30) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_issue_requests_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),
    CONSTRAINT fk_coupon_issue_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_coupon_issue_requests_submission UNIQUE (coupon_id, user_id, mode),
    CONSTRAINT chk_coupon_issue_requests_mode CHECK (mode IN ('DIRECT_KAFKA', 'OUTBOX')),
    CONSTRAINT chk_coupon_issue_requests_status CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED')),
    CONSTRAINT chk_coupon_issue_requests_result CHECK (
        result_code IS NULL OR result_code IN ('SOLD_OUT', 'ALREADY_ISSUED', 'NOT_STARTED')
    ),
    INDEX idx_coupon_issue_requests_owner (user_id, id),
    INDEX idx_coupon_issue_requests_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    claimed_at DATETIME(6) NULL,
    claim_token VARCHAR(36) NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_outbox_events_request FOREIGN KEY (request_id) REFERENCES coupon_issue_requests (id),
    CONSTRAINT uq_outbox_events_request UNIQUE (request_id),
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED')),
    CONSTRAINT chk_outbox_events_attempts CHECK (attempts >= 0),
    INDEX idx_outbox_events_pending (status, claimed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
