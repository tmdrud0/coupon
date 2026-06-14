param(
    [string]$Modes = "DirectKafka,Outbox",
    [int]$Vus = 20,
    [int]$Iterations = 100,
    [int]$Repeats = 3,
    [int]$CouponId = 1,
    [string]$HostBaseUrl = "http://localhost:8080",
    [string]$K6BaseUrl = "http://host.docker.internal:8080",
    [int]$DrainTimeoutSeconds = 120,
    [switch]$ResetKafkaTopic
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$summaryDir = Join-Path $repoRoot "build\load-test"
$runner = Join-Path $PSScriptRoot "run-async-load-test.ps1"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$comparisonCsv = Join-Path $summaryDir "async-comparison-$timestamp.csv"
$comparisonJson = Join-Path $summaryDir "async-comparison-$timestamp.json"
$rows = New-Object System.Collections.Generic.List[object]

if ($Vus -le 0 -or $Iterations -le 0 -or $Repeats -le 0) {
    throw "Vus, Iterations, and Repeats must be greater than zero."
}

$parsedModes = @($Modes -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($parsedModes.Count -eq 0) {
    throw "Modes must contain DirectKafka, Outbox, or both as a comma-separated string."
}
$invalidModes = @($parsedModes | Where-Object { $_ -notin @("DirectKafka", "Outbox") })
if ($invalidModes.Count -gt 0) {
    throw "Invalid Modes value(s): $($invalidModes -join ', '). Valid values are DirectKafka and Outbox."
}

foreach ($mode in $parsedModes) {
    for ($repeat = 1; $repeat -le $Repeats; $repeat += 1) {
        Write-Host "Running $mode repeat $repeat/$Repeats..."
        $before = @(Get-ChildItem $summaryDir -Filter "async-run-$($mode.ToLowerInvariant())-*.csv" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
        $arguments = @{
            Mode = $mode
            Vus = $Vus
            Iterations = $Iterations
            CouponId = $CouponId
            HostBaseUrl = $HostBaseUrl
            K6BaseUrl = $K6BaseUrl
            DrainTimeoutSeconds = $DrainTimeoutSeconds
            UsernamePrefix = "async-benchmark-r$repeat"
        }
        if ($ResetKafkaTopic) { $arguments.ResetKafkaTopic = $true }
        & $runner @arguments
        if ($LASTEXITCODE -ne 0) { throw "$mode repeat $repeat failed." }

        $after = @(Get-ChildItem $summaryDir -Filter "async-run-$($mode.ToLowerInvariant())-*.csv" | Sort-Object LastWriteTimeUtc -Descending)
        $newResult = $after | Where-Object { $before -notcontains $_.FullName } | Select-Object -First 1
        if (-not $newResult) { throw "Could not locate summary CSV for $mode repeat $repeat." }
        $row = Import-Csv $newResult.FullName
        $row | Add-Member -NotePropertyName Repeat -NotePropertyValue $repeat
        $rows.Add($row)
    }
}

$rows | Export-Csv -NoTypeInformation -Path $comparisonCsv
$aggregates = foreach ($group in ($rows | Group-Object Mode)) {
    $items = @($group.Group)
    [pscustomobject][ordered]@{
        Mode = $group.Name
        Runs = $items.Count
        MeanRequestsPerSecond = ($items | Measure-Object -Property RequestsPerSecond -Average).Average
        MeanP95Milliseconds = ($items | Measure-Object -Property P95Milliseconds -Average).Average
        MeanDrainSeconds = ($items | Measure-Object -Property DrainSeconds -Average).Average
        TotalUnexpectedResponses = ($items | Measure-Object -Property UnexpectedResponses -Sum).Sum
        TotalIssued = ($items | Measure-Object -Property ISSUED -Sum).Sum
        TotalRejected = ($items | Measure-Object -Property REJECTED -Sum).Sum
    }
}

[ordered]@{
    GeneratedAt = (Get-Date).ToString("o")
    Vus = $Vus
    Iterations = $Iterations
    Repeats = $Repeats
    Runs = $rows.ToArray()
    ByMode = @($aggregates)
} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -Path $comparisonJson

Write-Host ""
Write-Host "Comparison summaries"
Write-Host "  CSV: $comparisonCsv"
Write-Host "  JSON: $comparisonJson"
$aggregates | Format-Table Mode, Runs, MeanRequestsPerSecond, MeanP95Milliseconds, MeanDrainSeconds, TotalIssued, TotalRejected
