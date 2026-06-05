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

## Load test

Prepare deterministic data:

```powershell
Get-Content .\load-test\prepare-load-test.sql | docker exec -i coupon-mysql mysql -uroot -proot coupon
```

Run k6 through Docker:

```powershell
$root = (Get-Location).Path
docker run --rm -e BASE_URL=http://host.docker.internal:8080 -e COUPON_ID=1 -e VUS=100 -e ITERATIONS=1000 -v "${root}\load-test:/scripts" grafana/k6 run /scripts/issue-coupon.js
```

Verify the database:

```powershell
@'
SELECT
  (SELECT COUNT(*) FROM coupon_issues WHERE coupon_id = 1) AS issues,
  (SELECT COUNT(DISTINCT user_id) FROM coupon_issues WHERE coupon_id = 1) AS distinct_issue_users,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = 1 AND status = 'ISSUED') AS issued_slots,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = 1 AND status = 'AVAILABLE') AS available_slots;
'@ | docker exec -i coupon-mysql mysql -uroot -proot coupon
```
