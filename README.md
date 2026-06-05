# Coupon

First-come coupon issuing API using Spring Boot, MySQL, and `SELECT ... FOR UPDATE SKIP LOCKED`.

## Run locally

```powershell
docker compose up -d mysql
.\gradlew.bat bootRun
```

The Compose MySQL instance is exposed on local port `3307` to avoid collisions with an existing local MySQL.

## API quick start

```http
POST /api/auth/login
Content-Type: application/json

{"username":"alice"}
```

```http
GET /api/coupons
POST /api/coupons/1/issues
GET /api/me/coupon-issues
GET /api/coupons/1/stats
```

Duplicate issue requests return `409 ALREADY_ISSUED`.
