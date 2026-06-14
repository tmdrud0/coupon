param(
    [ValidateSet("DirectKafka", "Outbox")]
    [string]$Mode = $(if ($env:ASYNC_MODE) { $env:ASYNC_MODE } else { "DirectKafka" }),
    [string]$HostBaseUrl = $(if ($env:HOST_BASE_URL) { $env:HOST_BASE_URL } else { "http://localhost:8080" }),
    [string]$K6BaseUrl = $(if ($env:K6_BASE_URL) { $env:K6_BASE_URL } elseif ($env:BASE_URL) { $env:BASE_URL } else { "http://host.docker.internal:8080" }),
    [int]$Vus = $(if ($env:VUS) { [int]$env:VUS } else { 20 }),
    [int]$Iterations = $(if ($env:ITERATIONS) { [int]$env:ITERATIONS } else { 100 }),
    [int]$CouponId = $(if ($env:COUPON_ID) { [int]$env:COUPON_ID } else { 1 }),
    [int]$DrainTimeoutSeconds = $(if ($env:DRAIN_TIMEOUT_SECONDS) { [int]$env:DRAIN_TIMEOUT_SECONDS } else { 120 }),
    [int]$PollIntervalMilliseconds = $(if ($env:POLL_INTERVAL_MILLISECONDS) { [int]$env:POLL_INTERVAL_MILLISECONDS } else { 500 }),
    [string]$UsernamePrefix = $(if ($env:USERNAME_PREFIX) { $env:USERNAME_PREFIX } else { "async-load" }),
    [string]$RequestTable = $(if ($env:ASYNC_REQUEST_TABLE) { $env:ASYNC_REQUEST_TABLE } else { "coupon_issue_requests" }),
    [string[]]$ResetTables = @("coupon_issue_outbox", "coupon_issue_outbox_events", "outbox_events"),
    [string]$KafkaContainer = $(if ($env:KAFKA_CONTAINER) { $env:KAFKA_CONTAINER } else { "coupon-kafka" }),
    [string]$KafkaTopic = $(if ($env:KAFKA_TOPIC) { $env:KAFKA_TOPIC } else { "coupon-issue-requests" }),
    [int]$KafkaPartitions = $(if ($env:KAFKA_PARTITIONS) { [int]$env:KAFKA_PARTITIONS } else { 1 }),
    [switch]$ResetKafkaTopic
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$prepareSql = Join-Path $PSScriptRoot "prepare-load-test.sql"
$k6ScriptName = "async-submit-coupon.js"
$k6Script = Join-Path $PSScriptRoot $k6ScriptName
$summaryDir = Join-Path $repoRoot "build\load-test"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$runId = "$($Mode.ToLowerInvariant())-$timestamp"
$runUsernamePrefix = "$UsernamePrefix-$runId"
$sessionsFileName = "async-sessions-$runId.json"
$sessionsPath = Join-Path $summaryDir $sessionsFileName
$k6SummaryFileName = "async-k6-$runId.json"
$k6SummaryPath = Join-Path $summaryDir $k6SummaryFileName
$jsonPath = Join-Path $summaryDir "async-run-$runId.json"
$csvPath = Join-Path $summaryDir "async-run-$runId.csv"
$stockQuantity = 500

function Assert-PositiveInt {
    param([string]$Name, [int]$Value)
    if ($Value -le 0) {
        throw "$Name must be greater than zero."
    }
}

function Invoke-Docker {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
    return $output
}

function Invoke-MySql {
    param([string]$Sql)
    $output = $Sql | docker exec -i coupon-mysql mysql -uroot -proot coupon --batch --raw --skip-column-names
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed with exit code $LASTEXITCODE."
    }
    return @($output)
}

function Test-MySqlTable {
    param([string]$TableName)
    if ($TableName -notmatch '^[A-Za-z0-9_]+$') {
        throw "Invalid MySQL table name '$TableName'."
    }
    $escaped = $TableName.Replace("'", "''")
    $value = Invoke-MySql -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$escaped';"
    return [int]([string]($value | Select-Object -First 1)) -eq 1
}

function Get-TableColumns {
    param([string]$TableName)
    if ($TableName -notmatch '^[A-Za-z0-9_]+$') {
        throw "Invalid MySQL table name '$TableName'."
    }
    $escaped = $TableName.Replace("'", "''")
    return @(Invoke-MySql -Sql "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '$escaped' ORDER BY ordinal_position;")
}

function Wait-ForContainer {
    param([string]$Container, [string[]]$ProbeArguments, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & docker exec $Container @ProbeArguments 2>$null | Out-Null
        $probeExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($probeExitCode -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Container did not become ready within $TimeoutSeconds seconds."
}

function Get-LoginSessionId {
    param([string]$LoginUrl, [string]$Username)
    $webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $body = @{ username = $Username } | ConvertTo-Json -Compress
    $response = Invoke-WebRequest -Uri $LoginUrl -Method Post -ContentType "application/json" -Body $body -WebSession $webSession -UseBasicParsing
    if ($response.StatusCode -ne 200) {
        throw "Login failed for $Username with HTTP $($response.StatusCode)."
    }
    $cookie = $webSession.Cookies.GetCookies([Uri]$LoginUrl) | Where-Object { $_.Name -eq "JSESSIONID" } | Select-Object -First 1
    if (-not $cookie -or -not $cookie.Value) {
        throw "Login for $Username did not return JSESSIONID."
    }
    return $cookie.Value
}

function New-SessionsFile {
    $sessions = New-Object System.Collections.Generic.List[string]
    $loginUrl = "$($HostBaseUrl.TrimEnd('/'))/api/auth/login"
    Write-Host "Pre-authenticating $Iterations distinct users..."
    for ($index = 0; $index -lt $Iterations; $index += 1) {
        $sessions.Add((Get-LoginSessionId -LoginUrl $loginUrl -Username "$runUsernamePrefix-$index"))
    }
    $json = @{ sessions = $sessions.ToArray() } | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText($sessionsPath, $json, (New-Object System.Text.UTF8Encoding $false))
}

function Ensure-KafkaTopic {
    & docker inspect $KafkaContainer *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Kafka container '$KafkaContainer' is unavailable."
    }

    $bootstrap = "localhost:9092"
    $topicsCommand = "/opt/kafka/bin/kafka-topics.sh"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $topics = @(& docker exec $KafkaContainer $topicsCommand --bootstrap-server $bootstrap --list 2>$null)
    $listExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($listExitCode -ne 0) {
        throw "Unable to list Kafka topics from '$KafkaContainer'."
    }
    if ($topics -contains $KafkaTopic) {
        Write-Host "Kafka topic $KafkaTopic already exists; preserving topic and consumer offsets."
        return
    }

    Write-Host "Creating missing Kafka topic $KafkaTopic..."
    & docker exec $KafkaContainer $topicsCommand --bootstrap-server $bootstrap --create --if-not-exists --topic $KafkaTopic --partitions $KafkaPartitions --replication-factor 1 *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create Kafka topic '$KafkaTopic'."
    }
}

function Reset-KafkaTopicExplicitly {
    Write-Warning "Deleting Kafka topic '$KafkaTopic' can disrupt live consumers and distort drain-time measurements."
    $bootstrap = "localhost:9092"
    $topicsCommand = "/opt/kafka/bin/kafka-topics.sh"
    & docker exec $KafkaContainer $topicsCommand --bootstrap-server $bootstrap --delete --if-exists --topic $KafkaTopic *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to delete Kafka topic '$KafkaTopic'."
    }

    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $topics = @(& docker exec $KafkaContainer $topicsCommand --bootstrap-server $bootstrap --list 2>$null)
        $listExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($listExitCode -eq 0 -and $topics -notcontains $KafkaTopic) {
            Ensure-KafkaTopic
            return
        }
    } while ((Get-Date) -lt $deadline)

    throw "Kafka topic '$KafkaTopic' was not deleted within 30 seconds."
}

function Get-Metric {
    param([object]$Summary, [string]$Name)
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if ($property) { return $property.Value }
    return $null
}

function Get-MetricValue {
    param([AllowNull()][object]$Metric, [string]$Name, [double]$Default = 0)
    if ($null -eq $Metric) { return $Default }
    $property = $Metric.PSObject.Properties[$Name]
    if (-not $property) { return $Default }
    return [double]$property.Value
}

function Get-SubmissionMetrics {
    $summary = Get-Content -Raw $k6SummaryPath | ConvertFrom-Json
    $requests = Get-Metric -Summary $summary -Name "http_reqs{phase:submit}"
    $duration = Get-Metric -Summary $summary -Name "http_req_duration{phase:submit}"
    $unexpected = Get-Metric -Summary $summary -Name "async_submit_unexpected"
    return [ordered]@{
        RequestCount = [int](Get-MetricValue -Metric $requests -Name "count")
        RequestsPerSecond = Get-MetricValue -Metric $requests -Name "rate"
        AvgMilliseconds = Get-MetricValue -Metric $duration -Name "avg"
        P90Milliseconds = Get-MetricValue -Metric $duration -Name "p(90)"
        P95Milliseconds = Get-MetricValue -Metric $duration -Name "p(95)"
        MaxMilliseconds = Get-MetricValue -Metric $duration -Name "max"
        UnexpectedResponses = [int](Get-MetricValue -Metric $unexpected -Name "count")
    }
}

function Get-RequestAggregate {
    param([string[]]$Columns)
    foreach ($required in @("status", "user_id")) {
        if ($Columns -notcontains $required) {
            throw "$RequestTable must contain '$required' for aggregate polling."
        }
    }

    $resultCodeExpression = if ($Columns -contains "result_code") { "COALESCE(r.result_code, '')" } else { "''" }
    $escapedPrefix = $runUsernamePrefix.Replace("'", "''").Replace("_", "\_").Replace("%", "\%")
    $sql = @"
SELECT r.status, $resultCodeExpression AS result_code, COUNT(*)
FROM $RequestTable r
JOIN users u ON u.id = r.user_id
WHERE r.coupon_id = $CouponId
  AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\'
GROUP BY r.status, result_code
ORDER BY r.status, result_code;
"@
    $rows = Invoke-MySql -Sql $sql
    $groups = New-Object System.Collections.Generic.List[object]
    $counts = [ordered]@{ Total = 0; PENDING = 0; ISSUED = 0; REJECTED = 0 }
    foreach ($line in $rows) {
        if (-not $line) { continue }
        $parts = @($line -split "`t")
        $status = $parts[0]
        $resultCode = if ($parts.Count -ge 3) { $parts[1] } else { "" }
        $count = [int]$parts[$parts.Count - 1]
        $counts.Total += $count
        if ($counts.Contains($status)) {
            $counts[$status] += $count
        }
        $groups.Add([pscustomobject][ordered]@{ Status = $status; ResultCode = $resultCode; Count = $count })
    }
    return [pscustomobject]@{ Counts = $counts; Groups = $groups.ToArray() }
}

function Get-FinalDatabaseCounts {
    $escapedPrefix = $runUsernamePrefix.Replace("'", "''").Replace("_", "\_").Replace("%", "\%")
    $sql = @"
SELECT
  (SELECT COUNT(*)
   FROM coupon_issues ci
   JOIN users u ON u.id = ci.user_id
   WHERE ci.coupon_id = $CouponId
     AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\') AS issue_rows,
  (SELECT COUNT(DISTINCT ci.user_id)
   FROM coupon_issues ci
   JOIN users u ON u.id = ci.user_id
   WHERE ci.coupon_id = $CouponId
     AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\') AS distinct_issue_users,
  (SELECT COUNT(DISTINCT ci.stock_slot_id)
   FROM coupon_issues ci
   JOIN users u ON u.id = ci.user_id
   WHERE ci.coupon_id = $CouponId
     AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\') AS distinct_issue_stock_slots,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = $CouponId AND status = 'ISSUED') AS issued_stock_slots,
  (SELECT COUNT(*) FROM coupon_stock_slots WHERE coupon_id = $CouponId AND status = 'AVAILABLE') AS available_stock_slots,
  (SELECT COUNT(*)
   FROM $RequestTable r
   JOIN users u ON u.id = r.user_id
   WHERE r.coupon_id = $CouponId
     AND r.status = 'REJECTED'
     AND r.result_code = 'SOLD_OUT'
     AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\') AS sold_out_requests,
  (SELECT COUNT(DISTINCT r.user_id)
   FROM $RequestTable r
   JOIN users u ON u.id = r.user_id
   WHERE r.coupon_id = $CouponId
     AND r.status = 'REJECTED'
     AND r.result_code = 'SOLD_OUT'
     AND u.username LIKE '$escapedPrefix-%' ESCAPE '\\') AS distinct_sold_out_users;
"@
    $line = [string]((Invoke-MySql -Sql $sql) | Select-Object -First 1)
    $values = @($line -split "`t")
    if ($values.Count -ne 7) {
        throw "Unexpected final database count result: $line"
    }

    return [ordered]@{
        IssueRows = [int]$values[0]
        DistinctIssueUsers = [int]$values[1]
        DistinctIssueStockSlots = [int]$values[2]
        IssuedStockSlots = [int]$values[3]
        AvailableStockSlots = [int]$values[4]
        SoldOutRequests = [int]$values[5]
        DistinctSoldOutUsers = [int]$values[6]
    }
}

function Add-ValidationMismatch {
    param(
        [System.Collections.Generic.List[object]]$Mismatches,
        [string]$Name,
        [int]$Expected,
        [int]$Actual
    )
    if ($Expected -ne $Actual) {
        $Mismatches.Add([pscustomobject][ordered]@{
            Name = $Name
            Expected = $Expected
            Actual = $Actual
        })
    }
}

Assert-PositiveInt -Name "Vus" -Value $Vus
Assert-PositiveInt -Name "Iterations" -Value $Iterations
Assert-PositiveInt -Name "DrainTimeoutSeconds" -Value $DrainTimeoutSeconds
Assert-PositiveInt -Name "PollIntervalMilliseconds" -Value $PollIntervalMilliseconds
foreach ($path in @($prepareSql, $k6Script)) {
    if (-not (Test-Path $path)) { throw "Missing required file: $path" }
}

New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
$services = @(Invoke-Docker -Arguments @("compose", "--project-directory", $repoRoot, "config", "--services"))
$servicesToStart = @("mysql", "redis")
if ($services -contains "kafka") { $servicesToStart += "kafka" }
Invoke-Docker -Arguments (@("compose", "--project-directory", $repoRoot, "up", "-d") + $servicesToStart) | Out-Null
Wait-ForContainer -Container "coupon-mysql" -ProbeArguments @("mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot", "--silent")
Wait-ForContainer -Container "coupon-redis" -ProbeArguments @("redis-cli", "ping")
if ($services -contains "kafka") {
    Wait-ForContainer -Container $KafkaContainer -ProbeArguments @("/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list")
}

if (-not (Test-MySqlTable -TableName $RequestTable)) {
    throw "Async request table '$RequestTable' does not exist. Start the backend with the asynchronous migration before running this benchmark."
}

Write-Host "Resetting asynchronous load-test tables..."
$tablesToReset = @($ResetTables + $RequestTable) | Select-Object -Unique
$existingResetTables = @($tablesToReset | Where-Object { Test-MySqlTable -TableName $_ })
if ($existingResetTables.Count -gt 0) {
    $truncateSql = "SET FOREIGN_KEY_CHECKS=0;`n" + (($existingResetTables | ForEach-Object { "TRUNCATE TABLE $_;" }) -join "`n") + "`nSET FOREIGN_KEY_CHECKS=1;"
    Invoke-MySql -Sql $truncateSql | Out-Null
}

$prepareSqlText = "SET @coupon_id = $CouponId;`n" + (Get-Content -Raw $prepareSql)
$prepareSqlText | docker exec -i coupon-mysql mysql -uroot -proot coupon
if ($LASTEXITCODE -ne 0) { throw "Preparing load-test data failed with exit code $LASTEXITCODE." }
Invoke-Docker -Arguments @("exec", "coupon-redis", "redis-cli", "DEL", "coupon:${CouponId}:stock:remaining", "coupon:${CouponId}:users") | Out-Null
if ($ResetKafkaTopic) {
    Reset-KafkaTopicExplicitly
} else {
    Ensure-KafkaTopic
}
New-SessionsFile

$loadTestPath = Join-Path $repoRoot "load-test"
$expectedResponseMode = if ($Mode -eq "DirectKafka") { "DIRECT_KAFKA" } else { "OUTBOX" }
$k6Arguments = @(
    "run", "--rm",
    "-e", "BASE_URL=$K6BaseUrl",
    "-e", "COUPON_ID=$CouponId",
    "-e", "VUS=$Vus",
    "-e", "ITERATIONS=$Iterations",
    "-e", "ASYNC_MODE=$Mode",
    "-e", "EXPECTED_RESPONSE_MODE=$expectedResponseMode",
    "-e", "SESSIONS_FILE=/summary/$sessionsFileName",
    "-v", "${loadTestPath}:/scripts",
    "-v", "${summaryDir}:/summary",
    "grafana/k6", "run",
    "--summary-export", "/summary/$k6SummaryFileName",
    "/scripts/$k6ScriptName"
)

Write-Host "Submitting $Iterations asynchronous requests in $Mode mode..."
$submissionStartedAt = Get-Date
Invoke-Docker -Arguments $k6Arguments | Out-Host
$submissionFinishedAt = Get-Date
$submission = Get-SubmissionMetrics
if ($submission.RequestCount -ne $Iterations -or $submission.UnexpectedResponses -ne 0) {
    throw "Submission validation failed: requests=$($submission.RequestCount), expected=$Iterations, unexpected=$($submission.UnexpectedResponses)."
}

$requestColumns = Get-TableColumns -TableName $RequestTable
$drainStartedAt = Get-Date
$deadline = $drainStartedAt.AddSeconds($DrainTimeoutSeconds)
$aggregate = Get-RequestAggregate -Columns $requestColumns
while (($aggregate.Counts.Total -lt $Iterations -or $aggregate.Counts.PENDING -gt 0) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds $PollIntervalMilliseconds
    $aggregate = Get-RequestAggregate -Columns $requestColumns
}
$drainFinishedAt = Get-Date
$timedOut = $aggregate.Counts.Total -lt $Iterations -or $aggregate.Counts.PENDING -gt 0
$databaseCounts = Get-FinalDatabaseCounts
$expectedIssued = [Math]::Min($Iterations, $stockQuantity)
$expectedRejected = $Iterations - $expectedIssued
$expected = [ordered]@{
    RequestTotal = $Iterations
    PendingRequests = 0
    IssuedRequests = $expectedIssued
    RejectedRequests = $expectedRejected
    SoldOutRequests = $expectedRejected
    DistinctSoldOutUsers = $expectedRejected
    IssueRows = $expectedIssued
    DistinctIssueUsers = $expectedIssued
    DistinctIssueStockSlots = $expectedIssued
    IssuedStockSlots = $expectedIssued
    AvailableStockSlots = $stockQuantity - $expectedIssued
}
$actual = [ordered]@{
    RequestTotal = $aggregate.Counts.Total
    PendingRequests = $aggregate.Counts.PENDING
    IssuedRequests = $aggregate.Counts.ISSUED
    RejectedRequests = $aggregate.Counts.REJECTED
    SoldOutRequests = $databaseCounts.SoldOutRequests
    DistinctSoldOutUsers = $databaseCounts.DistinctSoldOutUsers
    IssueRows = $databaseCounts.IssueRows
    DistinctIssueUsers = $databaseCounts.DistinctIssueUsers
    DistinctIssueStockSlots = $databaseCounts.DistinctIssueStockSlots
    IssuedStockSlots = $databaseCounts.IssuedStockSlots
    AvailableStockSlots = $databaseCounts.AvailableStockSlots
}
$mismatches = New-Object System.Collections.Generic.List[object]
foreach ($name in $expected.Keys) {
    Add-ValidationMismatch -Mismatches $mismatches -Name $name -Expected $expected[$name] -Actual $actual[$name]
}
$requestIssuedMatchesIssueRows = $aggregate.Counts.ISSUED -eq $databaseCounts.IssueRows
if (-not $requestIssuedMatchesIssueRows) {
    Add-ValidationMismatch `
        -Mismatches $mismatches `
        -Name "RequestIssuedMatchesIssueRows" `
        -Expected $databaseCounts.IssueRows `
        -Actual $aggregate.Counts.ISSUED
}
$validationPassed = -not $timedOut -and $mismatches.Count -eq 0
$mismatchDetails = ($mismatches | ForEach-Object { "$($_.Name): expected $($_.Expected), actual $($_.Actual)" }) -join "; "

$result = [ordered]@{
    RunId = $runId
    Mode = $Mode
    CouponId = $CouponId
    Vus = $Vus
    Iterations = $Iterations
    UsernamePrefix = $runUsernamePrefix
    HostBaseUrl = $HostBaseUrl
    K6BaseUrl = $K6BaseUrl
    SubmissionStartedAt = $submissionStartedAt.ToString("o")
    SubmissionFinishedAt = $submissionFinishedAt.ToString("o")
    SubmissionSeconds = [Math]::Round(($submissionFinishedAt - $submissionStartedAt).TotalSeconds, 3)
    DrainSeconds = [Math]::Round(($drainFinishedAt - $drainStartedAt).TotalSeconds, 3)
    TimedOut = $timedOut
    RequestTable = $RequestTable
    K6SummaryPath = $k6SummaryPath
    Submission = $submission
    Final = [ordered]@{
        Total = $aggregate.Counts.Total
        PENDING = $aggregate.Counts.PENDING
        ISSUED = $aggregate.Counts.ISSUED
        REJECTED = $aggregate.Counts.REJECTED
        ByStatusAndResultCode = $aggregate.Groups
    }
    Validation = [ordered]@{
        Passed = $validationPassed
        Expected = $expected
        Actual = $actual
        RequestIssuedMatchesIssueRows = $requestIssuedMatchesIssueRows
        Mismatches = $mismatches.ToArray()
    }
}

$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $jsonPath
[pscustomobject][ordered]@{
    RunId = $runId
    Mode = $Mode
    Vus = $Vus
    Iterations = $Iterations
    RequestCount = $submission.RequestCount
    RequestsPerSecond = $submission.RequestsPerSecond
    AvgMilliseconds = $submission.AvgMilliseconds
    P90Milliseconds = $submission.P90Milliseconds
    P95Milliseconds = $submission.P95Milliseconds
    MaxMilliseconds = $submission.MaxMilliseconds
    UnexpectedResponses = $submission.UnexpectedResponses
    Total = $aggregate.Counts.Total
    PENDING = $aggregate.Counts.PENDING
    ISSUED = $aggregate.Counts.ISSUED
    REJECTED = $aggregate.Counts.REJECTED
    SoldOutRequests = $databaseCounts.SoldOutRequests
    DistinctSoldOutUsers = $databaseCounts.DistinctSoldOutUsers
    IssueRows = $databaseCounts.IssueRows
    DistinctIssueUsers = $databaseCounts.DistinctIssueUsers
    DistinctIssueStockSlots = $databaseCounts.DistinctIssueStockSlots
    IssuedStockSlots = $databaseCounts.IssuedStockSlots
    AvailableStockSlots = $databaseCounts.AvailableStockSlots
    ExpectedIssued = $expectedIssued
    ExpectedRejected = $expectedRejected
    ExpectedRequestTotal = $Iterations
    ExpectedPending = 0
    ExpectedSoldOutRequests = $expectedRejected
    ExpectedDistinctSoldOutUsers = $expectedRejected
    ExpectedIssueRows = $expectedIssued
    ExpectedDistinctIssueUsers = $expectedIssued
    ExpectedDistinctIssueStockSlots = $expectedIssued
    ExpectedIssuedStockSlots = $expectedIssued
    ExpectedAvailableStockSlots = $stockQuantity - $expectedIssued
    RequestIssuedMatchesIssueRows = $requestIssuedMatchesIssueRows
    ValidationPassed = $validationPassed
    MismatchDetails = $mismatchDetails
    DrainSeconds = $result.DrainSeconds
    TimedOut = $timedOut
    JsonPath = $jsonPath
} | Export-Csv -NoTypeInformation -Path $csvPath

Write-Host ""
Write-Host "Async load-test summary"
Write-Host "  Mode: $Mode"
Write-Host "  Requests: $($submission.RequestCount) ($([Math]::Round($submission.RequestsPerSecond, 2)) req/s)"
Write-Host "  Latency avg/p90/p95/max: $([Math]::Round($submission.AvgMilliseconds, 2)) / $([Math]::Round($submission.P90Milliseconds, 2)) / $([Math]::Round($submission.P95Milliseconds, 2)) / $([Math]::Round($submission.MaxMilliseconds, 2)) ms"
Write-Host "  Final total/pending/issued/rejected: $($aggregate.Counts.Total) / $($aggregate.Counts.PENDING) / $($aggregate.Counts.ISSUED) / $($aggregate.Counts.REJECTED)"
Write-Host "  Issues/users/slots: $($databaseCounts.IssueRows) / $($databaseCounts.DistinctIssueUsers) / $($databaseCounts.DistinctIssueStockSlots)"
Write-Host "  Stock issued/available: $($databaseCounts.IssuedStockSlots) / $($databaseCounts.AvailableStockSlots)"
Write-Host "  SOLD_OUT requests/users: $($databaseCounts.SoldOutRequests) / $($databaseCounts.DistinctSoldOutUsers)"
Write-Host "  Drain: $($result.DrainSeconds)s (timed out: $timedOut)"
Write-Host "  Validation passed: $validationPassed"
Write-Host "  JSON: $jsonPath"
Write-Host "  CSV: $csvPath"

if ($timedOut) {
    throw "Asynchronous requests did not drain within $DrainTimeoutSeconds seconds."
}
if ($mismatches.Count -gt 0) {
    throw "Final database validation failed: $mismatchDetails"
}
