param(
    [string]$HostBaseUrl = $(if ($env:HOST_BASE_URL) { $env:HOST_BASE_URL } else { "http://localhost:8080" }),
    [string]$K6BaseUrl = $(if ($env:K6_BASE_URL) { $env:K6_BASE_URL } elseif ($env:BASE_URL) { $env:BASE_URL } else { "http://host.docker.internal:8080" }),
    [int]$Vus = $(if ($env:VUS) { [int]$env:VUS } else { 100 }),
    [int]$Iterations = $(if ($env:ITERATIONS) { [int]$env:ITERATIONS } else { 1000 }),
    [int]$CouponId = $(if ($env:COUPON_ID) { [int]$env:COUPON_ID } else { 1 }),
    [string]$UsernamePrefix = $(if ($env:USERNAME_PREFIX) { $env:USERNAME_PREFIX } else { "load-test" }),
    [ValidateSet("EndToEnd", "IssueOnly")]
    [string]$Mode = $(if ($env:LOAD_TEST_MODE) { $env:LOAD_TEST_MODE } else { "EndToEnd" })
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$prepareSql = Join-Path $PSScriptRoot "prepare-load-test.sql"
$k6ScriptFileName = if ($Mode -eq "IssueOnly") { "issue-only-coupon.js" } else { "issue-coupon.js" }
$k6Script = Join-Path $PSScriptRoot $k6ScriptFileName
$summaryDir = Join-Path $repoRoot "build\load-test"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryPrefix = if ($Mode -eq "IssueOnly") { "k6-issue-only-summary" } else { "k6-summary" }
$summaryFileName = "$summaryPrefix-$timestamp.json"
$summaryPath = Join-Path $summaryDir $summaryFileName
$sessionsFileName = "issue-only-sessions-$timestamp.json"
$sessionsPath = Join-Path $summaryDir $sessionsFileName
$stockQuantity = 500
$expectedIssued = [Math]::Min($Iterations, $stockQuantity)
$expectedAvailable = $stockQuantity - $expectedIssued

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

function Get-RedisHealthStatus {
    $status = & docker inspect -f "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" coupon-redis 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    return $status.Trim()
}

function Wait-ForRedis {
    $deadline = (Get-Date).AddSeconds(120)

    while ((Get-Date) -lt $deadline) {
        $health = Get-RedisHealthStatus
        if ($health -eq "healthy") {
            return
        }

        if ($health -eq "none") {
            & docker exec coupon-redis redis-cli ping *> $null
            if ($LASTEXITCODE -eq 0) {
                return
            }
        }

        Start-Sleep -Seconds 2
    }

    $finalHealth = Get-RedisHealthStatus
    throw "coupon-redis did not become ready within 120 seconds. Last health status: $finalHealth"
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

function Get-LoginSessionId {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LoginUrl,

        [Parameter(Mandatory = $true)]
        [string]$Username
    )

    $webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $body = @{ username = $Username } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest `
        -Uri $LoginUrl `
        -Method Post `
        -ContentType "application/json" `
        -Body $body `
        -WebSession $webSession `
        -UseBasicParsing

    if ($response.StatusCode -ne 200) {
        throw "Login failed for $Username with HTTP $($response.StatusCode)."
    }

    $cookies = $webSession.Cookies.GetCookies([Uri]$LoginUrl)
    $sessionCookie = $cookies | Where-Object { $_.Name -eq "JSESSIONID" } | Select-Object -First 1
    if (-not $sessionCookie -or -not $sessionCookie.Value) {
        throw "Login for $Username did not return a JSESSIONID cookie."
    }

    return $sessionCookie.Value
}

function New-IssueOnlySessionsFile {
    $loginUrl = "$($HostBaseUrl.TrimEnd('/'))/api/auth/login"
    $sessions = New-Object System.Collections.Generic.List[string]

    Write-Host "Pre-authenticating $Iterations users against $HostBaseUrl..."
    for ($index = 0; $index -lt $Iterations; $index += 1) {
        $username = "$UsernamePrefix-$index"
        $sessions.Add((Get-LoginSessionId -LoginUrl $loginUrl -Username $username))
    }

    $sessionsJson = [ordered]@{
        sessions = $sessions.ToArray()
    } | ConvertTo-Json -Compress
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($sessionsPath, $sessionsJson, $utf8NoBom)

    return $sessionsPath
}

function Get-K6MetricCount {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [string]$MetricName
    )

    $metricProperty = $Summary.metrics.PSObject.Properties[$MetricName]
    if (-not $metricProperty) {
        return $null
    }

    $countProperty = $metricProperty.Value.PSObject.Properties["count"]
    if (-not $countProperty) {
        return $null
    }

    return [int]$countProperty.Value
}

function Assert-K6MetricCount {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [string]$MetricName,

        [Parameter(Mandatory = $true)]
        [int]$ExpectedCount,

        [bool]$AllowAbsent = $false
    )

    $actualCount = Get-K6MetricCount -Summary $Summary -MetricName $MetricName
    if ($null -eq $actualCount) {
        if ($AllowAbsent -or $ExpectedCount -eq 0) {
            $actualCount = 0
        } else {
            throw "k6 summary is missing metric count '$MetricName'."
        }
    }

    if ($actualCount -ne $ExpectedCount) {
        throw "k6 summary metric '$MetricName' expected $ExpectedCount but was $actualCount."
    }
}

function Assert-IssueOnlyK6Summary {
    if (-not (Test-Path $summaryPath)) {
        throw "Missing k6 summary: $summaryPath"
    }

    $summary = Get-Content -Raw $summaryPath | ConvertFrom-Json
    Assert-K6MetricCount -Summary $summary -MetricName "http_reqs" -ExpectedCount $Iterations

    if ($summary.metrics.PSObject.Properties["http_reqs{phase:issue}"]) {
        Assert-K6MetricCount -Summary $summary -MetricName "http_reqs{phase:issue}" -ExpectedCount $Iterations
    }

    Assert-K6MetricCount -Summary $summary -MetricName "issue_success" -ExpectedCount $expectedIssued
    Assert-K6MetricCount -Summary $summary -MetricName "issue_sold_out" -ExpectedCount ($Iterations - $expectedIssued)
    Assert-K6MetricCount -Summary $summary -MetricName "issue_already_issued" -ExpectedCount 0 -AllowAbsent $true
    Assert-K6MetricCount -Summary $summary -MetricName "issue_unexpected" -ExpectedCount 0 -AllowAbsent $true
}

if (-not (Test-Path $prepareSql)) {
    throw "Missing SQL file: $prepareSql"
}

if (-not (Test-Path $k6Script)) {
    throw "Missing k6 script: $k6Script"
}

New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null

Write-Host "Starting docker compose MySQL and Redis..."
Invoke-Docker -Arguments @("compose", "--project-directory", $repoRoot, "up", "-d", "mysql", "redis")

Write-Host "Waiting for coupon-mysql..."
Wait-ForMySql

Write-Host "Waiting for coupon-redis..."
Wait-ForRedis

Write-Host "Preparing deterministic load-test data..."
$prepareSqlText = "SET @coupon_id = $CouponId;`n" + (Get-Content -Raw $prepareSql)
$prepareSqlText | docker exec -i coupon-mysql mysql -uroot -proot coupon
if ($LASTEXITCODE -ne 0) {
    throw "Preparing load-test data failed with exit code $LASTEXITCODE."
}

Write-Host "Resetting Redis reservation state for coupon $CouponId..."
Invoke-Docker -Arguments @(
    "exec",
    "coupon-redis",
    "redis-cli",
    "DEL",
    "coupon:${CouponId}:stock:remaining",
    "coupon:${CouponId}:users"
)

$issueOnlySessionsPath = $null
if ($Mode -eq "IssueOnly") {
    $issueOnlySessionsPath = New-IssueOnlySessionsFile
}

Write-Host "Running k6 against $K6BaseUrl..."
$loadTestPath = Join-Path $repoRoot "load-test"
$k6DockerArguments = @(
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
    "/scripts/$k6ScriptFileName"
)

if ($Mode -eq "IssueOnly") {
    $k6DockerArguments = @(
        "run",
        "--rm",
        "-e", "BASE_URL=$K6BaseUrl",
        "-e", "COUPON_ID=$CouponId",
        "-e", "VUS=$Vus",
        "-e", "ITERATIONS=$Iterations",
        "-e", "USERNAME_PREFIX=$UsernamePrefix",
        "-e", "ISSUE_ONLY_SESSIONS_FILE=/summary/$sessionsFileName",
        "-v", "${loadTestPath}:/scripts",
        "-v", "${summaryDir}:/summary",
        "grafana/k6",
        "run",
        "--summary-export", "/summary/$summaryFileName",
        "/scripts/$k6ScriptFileName"
    )
}

Invoke-Docker -Arguments $k6DockerArguments

if ($Mode -eq "IssueOnly") {
    Write-Host "Verifying k6 issue-only summary..."
    Assert-IssueOnlyK6Summary
}

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
    issues = $expectedIssued
    distinct_issue_users = $expectedIssued
    distinct_issue_slots = $expectedIssued
    issued_slots = $expectedIssued
    available_slots = $expectedAvailable
}

$mismatches = @()
foreach ($name in $expected.Keys) {
    if ($actual[$name] -ne $expected[$name]) {
        $mismatches += "$name expected $($expected[$name]) but was $($actual[$name])"
    }
}

Write-Host ""
Write-Host "Load test summary"
Write-Host "  Mode: $Mode"
if ($Mode -eq "IssueOnly") {
    Write-Host "  Host base URL: $HostBaseUrl"
}
Write-Host "  Base URL: $K6BaseUrl"
Write-Host "  VUs: $Vus"
Write-Host "  Iterations: $Iterations"
Write-Host "  Prepared coupon slots: $stockQuantity"
Write-Host "  Expected successful issues: $expectedIssued"
Write-Host "  Coupon ID: $CouponId"
Write-Host "  Username prefix: $UsernamePrefix"
if ($Mode -eq "IssueOnly") {
    Write-Host "  Prepared sessions: $issueOnlySessionsPath"
}
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
