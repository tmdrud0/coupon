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

Run the Docker-based end-to-end load test helper while the Spring API is already running:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1
```

The default end-to-end mode logs in a distinct user and issues the coupon in each measured iteration. The helper starts Compose MySQL, prepares deterministic data with 500 coupon stock slots, runs `grafana/k6`, writes a k6 summary under `build\load-test`, and verifies the expected MySQL counts. Successful issues are capped at the prepared stock quantity, so `-Iterations 100` expects 100 issued slots and 400 available slots, while iterations above 500 expect 500 successful issues and the rest to return `SOLD_OUT`.

Use issue-only mode to pre-authenticate users in the PowerShell runner before k6 starts, then measure only `POST /api/coupons/{couponId}/issues` in the default k6 scenario:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1 -Mode IssueOnly
```

Issue-only session files are written as `build\load-test\issue-only-sessions-<timestamp>.json`, and issue-only summaries are written as `build\load-test\k6-issue-only-summary-<timestamp>.json`. Because login happens before k6 starts, k6 HTTP metrics contain issue requests only; with the default settings, total `http_reqs` is `1000`.

Common overrides:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1 -Mode EndToEnd -K6BaseUrl http://host.docker.internal:8080 -Vus 100 -Iterations 1000 -CouponId 1 -UsernamePrefix load-test
```

`Mode` defaults to `EndToEnd` and also supports `IssueOnly`. `HostBaseUrl` defaults to `http://localhost:8080` and is used by the PowerShell runner for issue-only pre-authentication. `K6BaseUrl` defaults to `http://host.docker.internal:8080` and is used by Docker k6. `K6BaseUrl` can be overridden with `-K6BaseUrl`, `K6_BASE_URL`, or `BASE_URL`; when both environment variables are set, `K6_BASE_URL` wins. Environment variables are also supported for `HOST_BASE_URL`, `LOAD_TEST_MODE`, `VUS`, `ITERATIONS`, `COUPON_ID`, and `USERNAME_PREFIX`.

```powershell
$env:K6_BASE_URL = 'http://host.docker.internal:8080'
$env:VUS = '100'
$env:ITERATIONS = '1000'
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1
```
