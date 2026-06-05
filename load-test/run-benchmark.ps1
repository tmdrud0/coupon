param(
    [ValidateSet("EndToEnd", "IssueOnly")]
    [string]$Mode = $(if ($env:LOAD_TEST_MODE) { $env:LOAD_TEST_MODE } else { "IssueOnly" }),
    [int[]]$VusList = @(20, 50, 100),
    [int[]]$IterationsList = @(100, 500, 1000),
    [int]$Repeats = $(if ($env:BENCHMARK_REPEATS) { [int]$env:BENCHMARK_REPEATS } else { 3 }),
    [string]$HostBaseUrl = $(if ($env:HOST_BASE_URL) { $env:HOST_BASE_URL } else { "http://localhost:8080" }),
    [string]$K6BaseUrl = $(if ($env:K6_BASE_URL) { $env:K6_BASE_URL } elseif ($env:BASE_URL) { $env:BASE_URL } else { "http://host.docker.internal:8080" }),
    [int]$CouponId = $(if ($env:COUPON_ID) { [int]$env:COUPON_ID } else { 1 }),
    [string]$UsernamePrefix = $(if ($env:USERNAME_PREFIX) { $env:USERNAME_PREFIX } else { "benchmark" })
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$summaryDir = Join-Path $repoRoot "build\load-test"
$loadTestScript = Join-Path $PSScriptRoot "run-load-test.ps1"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runCsvPath = Join-Path $summaryDir "benchmark-runs-$timestamp.csv"
$aggregateJsonPath = Join-Path $summaryDir "benchmark-aggregate-$timestamp.json"
$rawOutputDir = Join-Path $summaryDir "benchmark-output-$timestamp"
$mysqlStatusNames = @(
    "Threads_connected",
    "Threads_running",
    "Connections",
    "Slow_queries",
    "Innodb_row_lock_waits",
    "Innodb_row_lock_time",
    "Innodb_row_lock_time_max"
)

function Assert-PositiveInt {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [int]$Value
    )

    if ($Value -le 0) {
        throw "$Name must be greater than zero."
    }
}

function Invoke-MySqlStatusSnapshot {
    $quotedNames = ($mysqlStatusNames | ForEach-Object { "'$_'" }) -join ","
    $sql = "SHOW GLOBAL STATUS WHERE Variable_name IN ($quotedNames);"
    $snapshot = [ordered]@{}

    try {
        $output = $sql | docker exec -i coupon-mysql mysql -uroot -proot --batch --raw --skip-column-names
        if ($LASTEXITCODE -ne 0) {
            throw "docker exec mysql exited with $LASTEXITCODE"
        }

        foreach ($line in $output) {
            if (-not $line) {
                continue
            }

            $parts = @($line -split "`t", 2)
            if ($parts.Count -eq 2) {
                $snapshot[$parts[0]] = [int64]$parts[1]
            }
        }
    } catch {
        Write-Warning "Unable to capture MySQL status snapshot: $($_.Exception.Message)"
    }

    foreach ($name in $mysqlStatusNames) {
        if (-not $snapshot.Contains($name)) {
            $snapshot[$name] = $null
        }
    }

    return $snapshot
}

function Invoke-DockerStatsSnapshot {
    try {
        $json = & docker stats coupon-mysql --no-stream --format "{{json .}}"
        if ($LASTEXITCODE -ne 0) {
            throw "docker stats exited with $LASTEXITCODE"
        }

        if (-not $json) {
            return $null
        }

        return ($json | ConvertFrom-Json)
    } catch {
        Write-Warning "Unable to capture Docker stats for coupon-mysql: $($_.Exception.Message)"
        return $null
    }
}

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

function Get-Metric {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $property = $Summary.metrics.PSObject.Properties[$Name]
    if (-not $property) {
        return $null
    }

    return $property.Value
}

function Get-MetricValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowNull()]
        [object]$Metric,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if ($null -eq $Metric) {
        return $null
    }

    $property = $Metric.PSObject.Properties[$Name]
    if (-not $property) {
        return $null
    }

    return [double]$property.Value
}

function Get-CounterCount {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Summary,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $metric = Get-Metric -Summary $Summary -Name $Name
    $count = Get-MetricValue -Metric $metric -Name "count"
    if ($null -eq $count) {
        return 0
    }

    return [int]$count
}

function Get-K6Metrics {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SummaryPath
    )

    if (-not (Test-Path $SummaryPath)) {
        throw "Missing k6 summary JSON: $SummaryPath"
    }

    $summary = Get-Content -Raw $SummaryPath | ConvertFrom-Json
    $httpReqs = Get-Metric -Summary $summary -Name "http_reqs"
    $duration = Get-Metric -Summary $summary -Name "http_req_duration{phase:issue}"
    if ($null -eq $duration) {
        $duration = Get-Metric -Summary $summary -Name "http_req_duration"
    }

    return [ordered]@{
        HttpReqCount = [int](Get-MetricValue -Metric $httpReqs -Name "count")
        HttpReqRate = Get-MetricValue -Metric $httpReqs -Name "rate"
        DurationAvgMs = Get-MetricValue -Metric $duration -Name "avg"
        DurationP90Ms = Get-MetricValue -Metric $duration -Name "p(90)"
        DurationP95Ms = Get-MetricValue -Metric $duration -Name "p(95)"
        DurationMaxMs = Get-MetricValue -Metric $duration -Name "max"
        IssueSuccess = Get-CounterCount -Summary $summary -Name "issue_success"
        IssueSoldOut = Get-CounterCount -Summary $summary -Name "issue_sold_out"
        IssueAlreadyIssued = Get-CounterCount -Summary $summary -Name "issue_already_issued"
        IssueUnexpected = Get-CounterCount -Summary $summary -Name "issue_unexpected"
    }
}

function Get-FirstRegexValue {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string[]]$Lines,

        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    foreach ($line in $Lines) {
        if ($line -match $Pattern) {
            return $Matches[1].Trim()
        }
    }

    return $null
}

function ConvertTo-ProcessArgument {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Value
    )

    if ($Value -notmatch '[\s"]') {
        return $Value
    }

    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Invoke-LoadTestRun {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$StdoutPath,

        [Parameter(Mandatory = $true)]
        [string]$StderrPath
    )

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = "powershell"
    $process.StartInfo.Arguments = (($Arguments | ForEach-Object { ConvertTo-ProcessArgument -Value ([string]$_) }) -join " ")
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.CreateNoWindow = $true

    if (-not $process.Start()) {
        throw "Failed to start load-test process."
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdoutText = $stdoutTask.Result
    $stderrText = $stderrTask.Result

    [System.IO.File]::WriteAllText($StdoutPath, $stdoutText)
    [System.IO.File]::WriteAllText($StderrPath, $stderrText)

    $stdout = if ($stdoutText) { @($stdoutText -split "\r?\n") } else { @() }
    $stderr = if ($stderrText) { @($stderrText -split "\r?\n") } else { @() }

    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Lines = @($stdout + $stderr)
    }
}

function Get-NumberStats {
    param(
        [Parameter(Mandatory = $true)]
        [double[]]$Values
    )

    $filtered = @($Values | Where-Object { $null -ne $_ })
    if ($filtered.Count -eq 0) {
        return [ordered]@{
            Mean = $null
            Median = $null
            Min = $null
            Max = $null
            CoefficientOfVariation = $null
        }
    }

    $sorted = @($filtered | Sort-Object)
    $sum = 0.0
    foreach ($value in $filtered) {
        $sum += $value
    }

    $mean = $sum / $filtered.Count
    if (($sorted.Count % 2) -eq 1) {
        $median = $sorted[[int][Math]::Floor($sorted.Count / 2)]
    } else {
        $upperIndex = [int]($sorted.Count / 2)
        $median = ($sorted[$upperIndex - 1] + $sorted[$upperIndex]) / 2
    }

    $varianceSum = 0.0
    foreach ($value in $filtered) {
        $varianceSum += [Math]::Pow(($value - $mean), 2)
    }

    $stdDev = [Math]::Sqrt($varianceSum / $filtered.Count)
    $cv = if ($mean -ne 0.0) { $stdDev / $mean } else { $null }

    return [ordered]@{
        Mean = $mean
        Median = $median
        Min = $sorted[0]
        Max = $sorted[$sorted.Count - 1]
        CoefficientOfVariation = $cv
    }
}

foreach ($vus in $VusList) {
    Assert-PositiveInt -Name "VusList value" -Value $vus
}

foreach ($iterations in $IterationsList) {
    Assert-PositiveInt -Name "IterationsList value" -Value $iterations
}

Assert-PositiveInt -Name "Repeats" -Value $Repeats
if (-not (Test-Path $loadTestScript)) {
    throw "Missing load-test script: $loadTestScript"
}

New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
New-Item -ItemType Directory -Force -Path $rawOutputDir | Out-Null

Write-Host "Starting docker compose MySQL for benchmark snapshots..."
Invoke-Docker -Arguments @("compose", "--project-directory", $repoRoot, "up", "-d", "mysql")

Write-Host "Waiting for coupon-mysql before benchmark snapshots..."
Wait-ForMySql

$runRows = New-Object System.Collections.Generic.List[object]
$totalRuns = $VusList.Count * $IterationsList.Count * $Repeats
$runIndex = 0

foreach ($vus in $VusList) {
    foreach ($iterations in $IterationsList) {
        for ($repeat = 1; $repeat -le $Repeats; $repeat += 1) {
            $runIndex += 1
            $runId = "bench-$timestamp-v$vus-i$iterations-r$repeat"
            $runUsernamePrefix = "$UsernamePrefix-$runId"
            $rawOutputPath = Join-Path $rawOutputDir "$runId.log"
            $stdoutPath = Join-Path $rawOutputDir "$runId.stdout.log"
            $stderrPath = Join-Path $rawOutputDir "$runId.stderr.log"

            Write-Host "[$runIndex/$totalRuns] Running $Mode VUs=$vus Iterations=$iterations Repeat=$repeat"

            $mysqlBefore = Invoke-MySqlStatusSnapshot
            $dockerBefore = Invoke-DockerStatsSnapshot
            $startedAt = Get-Date

            $loadTestArguments = @(
                "-ExecutionPolicy", "Bypass",
                "-File", $loadTestScript,
                "-Mode", $Mode,
                "-HostBaseUrl", $HostBaseUrl,
                "-K6BaseUrl", $K6BaseUrl,
                "-Vus", $vus,
                "-Iterations", $iterations,
                "-CouponId", $CouponId,
                "-UsernamePrefix", $runUsernamePrefix
            )
            $loadTestResult = Invoke-LoadTestRun -Arguments $loadTestArguments -StdoutPath $stdoutPath -StderrPath $stderrPath
            $exitCode = $loadTestResult.ExitCode
            $finishedAt = Get-Date

            $outputLines = @($loadTestResult.Lines | ForEach-Object { $_.ToString() })
            [System.IO.File]::WriteAllLines($rawOutputPath, $outputLines)

            if ($exitCode -ne 0) {
                throw "Load-test run $runId failed with exit code $exitCode. Output saved to $rawOutputPath"
            }

            $mysqlAfter = Invoke-MySqlStatusSnapshot
            $dockerAfter = Invoke-DockerStatsSnapshot

            $summaryPath = Get-FirstRegexValue -Lines $outputLines -Pattern "^\s*k6 summary:\s*(.+)$"
            if (-not $summaryPath) {
                throw "Load-test run $runId did not print a k6 summary path. Output saved to $rawOutputPath"
            }

            $k6 = Get-K6Metrics -SummaryPath $summaryPath

            $row = [ordered]@{
                RunId = $runId
                Mode = $Mode
                Vus = $vus
                Iterations = $iterations
                Repeat = $repeat
                CouponId = $CouponId
                UsernamePrefix = $runUsernamePrefix
                HostBaseUrl = $HostBaseUrl
                K6BaseUrl = $K6BaseUrl
                StartedAt = $startedAt.ToString("o")
                FinishedAt = $finishedAt.ToString("o")
                DurationSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 3)
                K6SummaryPath = $summaryPath
                RawOutputPath = $rawOutputPath
                HttpReqCount = $k6.HttpReqCount
                HttpReqRate = $k6.HttpReqRate
                DurationAvgMs = $k6.DurationAvgMs
                DurationP90Ms = $k6.DurationP90Ms
                DurationP95Ms = $k6.DurationP95Ms
                DurationMaxMs = $k6.DurationMaxMs
                IssueSuccess = $k6.IssueSuccess
                IssueSoldOut = $k6.IssueSoldOut
                IssueAlreadyIssued = $k6.IssueAlreadyIssued
                IssueUnexpected = $k6.IssueUnexpected
                DockerBeforeCpuPerc = if ($dockerBefore) { $dockerBefore.CPUPerc } else { $null }
                DockerBeforeMemUsage = if ($dockerBefore) { $dockerBefore.MemUsage } else { $null }
                DockerBeforeNetIO = if ($dockerBefore) { $dockerBefore.NetIO } else { $null }
                DockerBeforeBlockIO = if ($dockerBefore) { $dockerBefore.BlockIO } else { $null }
                DockerAfterCpuPerc = if ($dockerAfter) { $dockerAfter.CPUPerc } else { $null }
                DockerAfterMemUsage = if ($dockerAfter) { $dockerAfter.MemUsage } else { $null }
                DockerAfterNetIO = if ($dockerAfter) { $dockerAfter.NetIO } else { $null }
                DockerAfterBlockIO = if ($dockerAfter) { $dockerAfter.BlockIO } else { $null }
            }

            foreach ($name in $mysqlStatusNames) {
                $before = $mysqlBefore[$name]
                $after = $mysqlAfter[$name]
                $row["MySqlBefore$name"] = $before
                $row["MySqlAfter$name"] = $after
                $row["MySqlDelta$name"] = if ($null -ne $before -and $null -ne $after) { $after - $before } else { $null }
            }

            $runRows.Add([pscustomobject]$row)
            Write-Host "  req/s=$($k6.HttpReqRate) p95=$($k6.DurationP95Ms)ms summary=$summaryPath"
        }
    }
}

$runRows | Export-Csv -NoTypeInformation -Path $runCsvPath

$scenarioAggregates = New-Object System.Collections.Generic.List[object]
$groups = $runRows | Group-Object -Property Mode,Vus,Iterations
foreach ($group in $groups) {
    $first = $group.Group[0]
    $reqRateStats = Get-NumberStats -Values ([double[]]@($group.Group | ForEach-Object { [double]$_.HttpReqRate }))
    $p95Stats = Get-NumberStats -Values ([double[]]@($group.Group | ForEach-Object { [double]$_.DurationP95Ms }))

    $scenarioAggregates.Add([pscustomobject][ordered]@{
        Mode = $first.Mode
        Vus = $first.Vus
        Iterations = $first.Iterations
        Repeats = $group.Count
        HttpReqRate = $reqRateStats
        DurationP95Ms = $p95Stats
    })
}

$aggregate = [ordered]@{
    GeneratedAt = (Get-Date).ToString("o")
    Mode = $Mode
    HostBaseUrl = $HostBaseUrl
    K6BaseUrl = $K6BaseUrl
    CouponId = $CouponId
    UsernamePrefix = $UsernamePrefix
    RunCsvPath = $runCsvPath
    RawOutputDir = $rawOutputDir
    Scenarios = $scenarioAggregates.ToArray()
    Runs = $runRows.ToArray()
}

$aggregate | ConvertTo-Json -Depth 12 | Set-Content -Path $aggregateJsonPath

Write-Host ""
Write-Host "Benchmark summary"
Write-Host "  Runs: $($runRows.Count)"
Write-Host "  Per-run CSV: $runCsvPath"
Write-Host "  Aggregate JSON: $aggregateJsonPath"
Write-Host "  Raw outputs: $rawOutputDir"
