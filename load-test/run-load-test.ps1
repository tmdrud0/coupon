param(
    [string]$K6BaseUrl = $(if ($env:K6_BASE_URL) { $env:K6_BASE_URL } elseif ($env:BASE_URL) { $env:BASE_URL } else { "http://host.docker.internal:8080" }),
    [int]$Vus = $(if ($env:VUS) { [int]$env:VUS } else { 100 }),
    [int]$Iterations = $(if ($env:ITERATIONS) { [int]$env:ITERATIONS } else { 1000 }),
    [int]$CouponId = $(if ($env:COUPON_ID) { [int]$env:COUPON_ID } else { 1 }),
    [string]$UsernamePrefix = $(if ($env:USERNAME_PREFIX) { $env:USERNAME_PREFIX } else { "load-test" })
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$prepareSql = Join-Path $PSScriptRoot "prepare-load-test.sql"
$k6Script = Join-Path $PSScriptRoot "issue-coupon.js"
$summaryDir = Join-Path $repoRoot "build\load-test"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryFileName = "k6-summary-$timestamp.json"
$summaryPath = Join-Path $summaryDir $summaryFileName

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Get-MySqlHealthStatus {
    $status = & docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" coupon-mysql 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    return $status.Trim()
}

function Wait-ForMySql {
    $deadline = (Get-Date).AddSeconds(120)

    while ((Get-Date) -lt $deadline) {
        $health = Get-MySqlHealthStatus
        if ($health -eq "healthy") {
            return
        }

        if ($health -eq "none") {
            & docker exec coupon-mysql mysqladmin ping -h localhost -uroot -proot --silent *> $null
            if ($LASTEXITCODE -eq 0) {
                return
            }
        }

        Start-Sleep -Seconds 2
    }

    $finalHealth = Get-MySqlHealthStatus
    throw "coupon-mysql did not become ready within 120 seconds. Last health status: $finalHealth"
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql
    )

    $output = $Sql | docker exec -i coupon-mysql mysql -uroot -proot coupon --batch --raw --skip-column-names
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed with exit code $LASTEXITCODE."
    }

    return $output
}

if (-not (Test-Path $prepareSql)) {
    throw "Missing SQL file: $prepareSql"
}

if (-not (Test-Path $k6Script)) {
    throw "Missing k6 script: $k6Script"
}

New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null

Write-Host "Starting docker compose MySQL..."
Invoke-Docker -Arguments @("compose", "--project-directory", $repoRoot, "up", "-d", "mysql")

Write-Host "Waiting for coupon-mysql..."
Wait-ForMySql

Write-Host "Preparing deterministic load-test data..."
$prepareSqlText = "SET @coupon_id = $CouponId;`n" + (Get-Content -Raw $prepareSql)
$prepareSqlText | docker exec -i coupon-mysql mysql -uroot -proot coupon
if ($LASTEXITCODE -ne 0) {
    throw "Preparing load-test data failed with exit code $LASTEXITCODE."
}

Write-Host "Running k6 against $K6BaseUrl..."
$loadTestPath = Join-Path $repoRoot "load-test"
Invoke-Docker -Arguments @(
    "run",
    "--rm",
    "-e", "BASE_URL=$K6BaseUrl",
    "-e", "COUPON_ID=$CouponId",
    "-e", "VUS=$Vus",
    "-e", "ITERATIONS=$Iterations",
    "-e", "USERNAME_PREFIX=$UsernamePrefix",
    "-v", "${loadTestPath}:/scripts",
    "-v", "${summaryDir}:/summary",
    "grafana/k6",
    "run",
    "--summary-export", "/summary/$summaryFileName",
    "/scripts/issue-coupon.js"
)

$verificationSql = @"
SELECT
  (SELECT COUNT(*) FROM coupon_issues WHERE coupon_id = $CouponId) AS issues,
  (SELECT COUNT(DISTINCT user_id) FROM coupon_issues WHERE coupon_id = $CouponId) AS distinct_issue_users,
  (SELECT COUNT(DISTINCT stock_slot_id) FROM coupon_issues WHERE coupon_id = $CouponId) AS distinct_issue_slots,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = $CouponId AND status = 'ISSUED') AS issued_slots,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = $CouponId AND status = 'AVAILABLE') AS available_slots;
"@

Write-Host "Verifying MySQL counts..."
$countsLine = Invoke-MySql -Sql $verificationSql
$values = @($countsLine -split "`t")
if ($values.Count -ne 5) {
    throw "Unexpected verification result: $countsLine"
}

$actual = [ordered]@{
    issues = [int]$values[0]
    distinct_issue_users = [int]$values[1]
    distinct_issue_slots = [int]$values[2]
    issued_slots = [int]$values[3]
    available_slots = [int]$values[4]
}

$expected = [ordered]@{
    issues = 500
    distinct_issue_users = 500
    distinct_issue_slots = 500
    issued_slots = 500
    available_slots = 0
}

$mismatches = @()
foreach ($name in $expected.Keys) {
    if ($actual[$name] -ne $expected[$name]) {
        $mismatches += "$name expected $($expected[$name]) but was $($actual[$name])"
    }
}

Write-Host ""
Write-Host "Load test summary"
Write-Host "  Base URL: $K6BaseUrl"
Write-Host "  VUs: $Vus"
Write-Host "  Iterations: $Iterations"
Write-Host "  Coupon ID: $CouponId"
Write-Host "  Username prefix: $UsernamePrefix"
Write-Host "  k6 summary: $summaryPath"
foreach ($name in $actual.Keys) {
    Write-Host "  ${name}: $($actual[$name])"
}

if ($mismatches.Count -gt 0) {
    Write-Host ""
    Write-Error "Verification failed: $($mismatches -join '; ')"
    exit 1
}

Write-Host ""
Write-Host "Verification passed."
