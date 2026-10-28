param(
    [Parameter(Mandatory)][string]$EvidenceRoot,
    [Parameter(Mandatory)][string]$PlanDigest
)
$ErrorActionPreference = 'Stop'
$output = Join-Path $EvidenceRoot 'process-index.json'
if (Test-Path -LiteralPath $output) { throw '기존 index를 덮어쓸 수 없습니다. 새 evidence 디렉터리를 사용하세요.' }
function Hash([string]$Relative) {
    return (Get-FileHash -LiteralPath (Join-Path $EvidenceRoot $Relative) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$reports = [ordered]@{}
foreach ($kind in @('events','waitlist')) {
    $relative = if ($kind -eq 'events') {'events/raw/report.json'} else {'waitlist/report.json'}
    $report = Get-Content -LiteralPath (Join-Path $EvidenceRoot $relative) -Raw | ConvertFrom-Json
    $reports[$kind] = [ordered]@{reportSha256=(Hash $relative); runId=$report.environment.runId;
        applicationRevision=$report.environment.applicationRevision; recordedAt=$report.environment.recordedAt}
}
$cases = @(Get-ChildItem -LiteralPath (Join-Path $EvidenceRoot 'agent') -Directory | ForEach-Object {
    $folder = "agent/$($_.Name)"
    [ordered]@{directory=$folder; summarySha256=(Hash "$folder/summary.json");
        preparedSha256=(Hash "$folder/original/prepared.json"); restartedSha256=(Hash "$folder/restart/restarted.json");
        readinessSha256=(Hash "$folder/restart/readiness.json")}
})
[ordered]@{schemaVersion='slotq-m8-recovery-process-index/v1'; planDigest=$PlanDigest; reports=$reports;
    agent=@{junitSha256=(Hash 'junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml'); cases=$cases}} |
    ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $output -Encoding utf8
Write-Output (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
