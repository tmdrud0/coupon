# Coupon

First-come coupon issuing API using Spring Boot, Redis, MySQL, Kafka, and `SELECT ... FOR UPDATE SKIP LOCKED`.

## Run locally

```powershell
docker compose up -d mysql redis kafka
.\gradlew.bat bootRun
```

The Compose MySQL instance is exposed on local port `3307` to avoid collisions with an existing local MySQL. Redis is exposed on local port `6379`, and Kafka is exposed on local port `9092`.

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

Duplicate issue requests return `409 ALREADY_ISSUED`. Sold-out requests return `409 SOLD_OUT`.

## Asynchronous coupon issue requests

Authenticated users can submit a request through either asynchronous model:

```http
POST /api/coupons/1/issue-requests/kafka
POST /api/coupons/1/issue-requests/outbox
GET /api/coupon-issue-requests/123
```

Submission returns `202 Accepted` with a request ID, mode, and `PENDING` status. Status lookup is restricted to the user who submitted the request. Final status is `ISSUED` or `REJECTED`; rejection reasons are `SOLD_OUT`, `ALREADY_ISSUED`, or `NOT_STARTED`. A coupon that has not started is normally rejected during submission with `409 NOT_STARTED`.

`DIRECT_KAFKA` persists and commits the request first, then waits synchronously for the Kafka broker acknowledgement. This intentionally has a dual-write gap: a process crash after the database commit and before publication can leave a pending request unpublished. A publish failure or timeout returns `503 KAFKA_UNAVAILABLE`, but does not prove that Kafka rejected the record, so the request remains `PENDING`. Retrying may publish the same request again; consumer idempotency makes duplicate delivery safe.

`OUTBOX` inserts the request and outbox event in one MySQL transaction. A scheduled publisher claims rows with `FOR UPDATE SKIP LOCKED` and a claim token, publishes them with `couponId` as the Kafka key, and marks them published after broker acknowledgement. Stale claims are retried, so delivery is at least once. Kafka preserves record arrival order within the partition selected by `couponId`; multiple outbox publisher instances do not guarantee that records reach Kafka in original database creation order, so this model does not promise first-submission fairness across publisher instances.

Both modes use the same Kafka consumer. It locks the request, ignores already-final requests, and performs final stock allocation and issue persistence in one MySQL transaction without Redis. The `(coupon_id, user_id)` issue constraint and request status make duplicate delivery idempotent.

## Load test

Run the Docker-based end-to-end load test helper while the Spring API is already running:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1
```

The default end-to-end mode logs in a distinct user and issues the coupon in each measured iteration. The helper starts Compose MySQL and Redis, prepares deterministic data with 500 coupon stock slots, resets the coupon's Redis reservation keys, runs `grafana/k6`, writes a k6 summary under `build\load-test`, and verifies the expected MySQL counts. Successful issues are capped at the prepared stock quantity, so `-Iterations 100` expects 100 issued slots and 400 available slots, while iterations above 500 expect 500 successful issues and the rest to return `SOLD_OUT`.

Use issue-only mode to pre-authenticate users in the PowerShell runner before k6 starts, then measure only `POST /api/coupons/{couponId}/issues` in the default k6 scenario:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-load-test.ps1 -Mode IssueOnly
```

Issue-only session files are written as `build\load-test\issue-only-sessions-<timestamp>.json`, and issue-only summaries are written as `build\load-test\k6-issue-only-summary-<timestamp>.json`. Because login happens before k6 starts, k6 HTTP metrics contain issue requests only; with the default settings, total `http_reqs` is `1000`.

Run a quick benchmark validation with repeated issue-only scenarios:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-benchmark.ps1 -Mode IssueOnly -VusList 20 -IterationsList 100 -Repeats 2
```

The benchmark runner calls `run-load-test.ps1` for each scenario and repeat, using a unique username prefix per run. It writes per-run CSV and aggregate JSON files under `build\load-test`, captures k6 request and latency metrics, records selected MySQL status counters before and after each run, and includes a best-effort Docker stats snapshot for `coupon-mysql`.

Run only one load test or benchmark runner at a time. These helpers reset the shared load-test MySQL tables before each run, so concurrent executions invalidate the measurements.

For a longer reliability run, pass multiple VU and iteration values:

```powershell
powershell -ExecutionPolicy Bypass -File .\load-test\run-benchmark.ps1 -Mode IssueOnly -VusList 100,300,500 -IterationsList 10000 -Repeats 3
```

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
