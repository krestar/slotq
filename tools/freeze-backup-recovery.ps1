param(
    [Parameter(Mandatory)][string]$PilotResult,
    [Parameter(Mandatory)][string]$Capacity16,
    [Parameter(Mandatory)][string]$Capacity64,
    [Parameter(Mandatory)][string]$RecoveryPlan,
    [Parameter(Mandatory)][string]$PreviousBinary,
    [Parameter(Mandatory)][string]$CandidateBinary,
    [Parameter(Mandatory)][long]$RpoMillis,
    [Parameter(Mandatory)][long]$RtoMillis,
    [Parameter(Mandatory)][string]$Reason,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
$destination = [IO.Path]::GetFullPath($Output)
if (-not $destination.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $destination)) {
    throw 'Use a new file below the repository ignored build directory.'
}
function Evidence([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    return @{ path = $resolved; sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$pilot = Get-Content -LiteralPath $PilotResult -Raw | ConvertFrom-Json
$recovery = Get-Content -LiteralPath $RecoveryPlan -Raw | ConvertFrom-Json
if ($recovery.state -cne 'FROZEN' -or $recovery.gates.restartReadyMillis -ne 60000 -or
    $recovery.gates.durableConvergenceMillis -ne 90000 -or $recovery.gates.postRecoveryQuietMillis -ne 15000) { throw 'Missing original #182 recovery criteria.' }
foreach ($point in @(@{path=$Capacity16;size=16;rate=20}, @{path=$Capacity64;size=64;rate=5})) {
    $capacity = Get-Content -LiteralPath $point.path -Raw | ConvertFrom-Json
    $binding = @($recovery.supportInputs | Where-Object { $_.size -eq $point.size -and $_.rate -eq $point.rate -and $_.seed -eq 18101 -and $_.pool -eq 4 })
    if ($capacity.state -cne 'FROZEN' -or $capacity.size -ne $point.size -or $capacity.rate -ne $point.rate -or $capacity.pool -ne 4 -or
        $capacity.seed -ne 18101 -or $binding.Count -ne 1 -or (Evidence $point.path).sha256 -cne $binding[0].criteriaDigest) { throw 'Mismatched #181/#182 support handoff.' }
}
if ($pilot.kind -ne 'PILOT_EXECUTION' -or $pilot.complete -ne $true -or
    $pilot.rpoMillis -le 0 -or $pilot.rtoMillis -le 0 -or
    $RpoMillis -lt $pilot.rpoMillis -or $RtoMillis -lt $pilot.rtoMillis -or [string]::IsNullOrWhiteSpace($Reason)) {
    throw 'A completed measured pilot and an explicit prospective criterion rationale are required.'
}
$manifestPath = Join-Path (Split-Path (Split-Path $PilotResult -Parent) -Parent) 'manifest.json'
$manifestEvidence = Evidence $manifestPath
if ($manifestEvidence.sha256 -ne $pilot.sourceManifestSha256) { throw 'Pilot source manifest was replaced.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$deployment = Evidence (Join-Path (Split-Path $PilotResult -Parent) 'deployment-contract.json')
if ($deployment.sha256 -cne $pilot.artifactDigests.'deployment-contract.json') { throw 'Pilot deployment configuration changed.' }
foreach ($entry in $manifest.sourceDigests.PSObject.Properties) {
    $current = Evidence (Join-Path (Join-Path $repo 'backend') $entry.Name)
    if ($current.sha256 -ne $entry.Value) { throw "Source changed since pilot: $($entry.Name). Run a new pilot." }
}
$plan = [ordered]@{
    schemaVersion = 'slotq-m8-backup-plan/v1'; state = 'FROZEN'; frozenAt = [DateTime]::UtcNow.ToString('o')
    method = 'QUIESCED_LOGICAL_DUMP'; retentionDays = 90
    access = 'Owner-only inherited Windows ACL; container client files mode 0600; no raw evidence in Git'
    storage = 'New ignored local build directory; logical recovery rehearsal, no independent-host disaster durability claim'
    rpoDefinition = 'Original source writer cutoff minus the quiesced backup recovery point; include explicitly observed lost post-backup commit'
    rtoDefinition = 'Before empty target MySQL provisioning until security reconciliation, binary readiness and final independent DB oracle'
    rpoMillis = $RpoMillis; rtoMillis = $RtoMillis
    restartReadyMillis = 60000; durableConvergenceMillis = 90000; postRecoveryQuietMillis = 15000
    reason = $Reason; pilot = (Evidence $PilotResult); pilotManifest = $manifestEvidence
    capacity16 = (Evidence $Capacity16); capacity64 = (Evidence $Capacity64); recoveryPlan = (Evidence $RecoveryPlan)
    previousBinary = (Evidence $PreviousBinary); candidateBinary = (Evidence $CandidateBinary)
    deploymentConfigSha256 = $deployment.sha256
    sourceDigests = $manifest.sourceDigests
    unsupported = @('HA', 'PITR', 'incremental backup', 'Kafka cutover', 'unmeasured scale or load', 'missing current security authority', 'restored Agent Run')
}
$plan | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $destination -Encoding utf8NoBOM
(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
