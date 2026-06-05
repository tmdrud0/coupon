CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL,
    issue_start_at DATETIME(6) NOT NULL,
    total_quantity INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_coupons_total_quantity_positive CHECK (total_quantity > 0),
    INDEX idx_coupons_issue_start_at (issue_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupon_stock_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_to_user_id BIGINT NULL,
    issued_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_stock_slots_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),
    CONSTRAINT fk_coupon_stock_slots_issued_user FOREIGN KEY (issued_to_user_id) REFERENCES users (id),
    CONSTRAINT chk_coupon_stock_slots_status CHECK (status IN ('AVAILABLE', 'ISSUED')),
    INDEX idx_coupon_stock_slots_available (coupon_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE coupon_issues (
    id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    stock_slot_id BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_issues_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id),
    CONSTRAINT fk_coupon_issues_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_coupon_issues_stock_slot FOREIGN KEY (stock_slot_id) REFERENCES coupon_stock_slots (id),
    CONSTRAINT uq_coupon_issues_coupon_user UNIQUE (coupon_id, user_id),
    CONSTRAINT uq_coupon_issues_stock_slot UNIQUE (stock_slot_id),
    INDEX idx_coupon_issues_user (user_id, issued_at),
    INDEX idx_coupon_issues_coupon (coupon_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
