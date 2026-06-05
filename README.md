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

Run the Docker-based load test helper while the Spring API is already running:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1
```

The script starts Compose MySQL, prepares deterministic data, runs `grafana/k6`, writes a k6 summary under `build\load-test`, and verifies the expected MySQL counts.

Common overrides:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1 -K6BaseUrl http://host.docker.internal:8080 -Vus 100 -Iterations 1000 -CouponId 1 -UsernamePrefix load-test
```

`K6BaseUrl` defaults to `http://host.docker.internal:8080`. It can be overridden with `-K6BaseUrl`, `K6_BASE_URL`, or `BASE_URL`; when both environment variables are set, `K6_BASE_URL` wins. Environment variables are also supported for `VUS`, `ITERATIONS`, `COUPON_ID`, and `USERNAME_PREFIX`.

```powershell
$env:K6_BASE_URL = 'http://host.docker.internal:8080'
$env:VUS = '100'
$env:ITERATIONS = '1000'
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1
```
