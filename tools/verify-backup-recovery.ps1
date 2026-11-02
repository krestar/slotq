param(
    [Parameter(Mandatory)][string]$Result,
    [Parameter(Mandatory)][string]$ResultDigest,
    [Parameter(Mandatory)][string]$Plan,
    [Parameter(Mandatory)][string]$PlanDigest,
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
$checks = [Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Present, [bool]$Valid) {
    $status = if (-not $Present) { 'INCOMPLETE' } elseif ($Valid) { 'PASS' } else { 'FAIL' }
    $checks.Add(@{ check = $Name; status = $status })
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Timestamp($Value) { return [DateTimeOffset]$Value }
function Pinned([string]$Path, [string]$Expected, [string]$Name) {
    $present = (Test-Path -LiteralPath $Path -PathType Leaf) -and ($Expected -match '^[a-f0-9]{64}$')
    $valid = $present -and ((Hash $Path) -ceq $Expected)
    Check $Name $present $valid
    return $valid
}
if (Test-Path -LiteralPath $Output) { throw 'Never overwrite a prior evidence review.' }
$repo = Split-Path $PSScriptRoot -Parent
$safeRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
if (-not [IO.Path]::GetFullPath($Output).StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Review output must be below ignored root build/.' }
$record = $null
try {
    $resultOk = Pinned $Result $ResultDigest 'result-original-digest'
    $planOk = Pinned $Plan $PlanDigest 'frozen-plan-original-digest'
    if ($resultOk -and $planOk) {
        $record = Get-Content -LiteralPath $Result -Raw | ConvertFrom-Json
        $criteria = Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json
        foreach ($name in @('pilot','pilotManifest','capacity16','capacity64','recoveryPlan','previousBinary','candidateBinary')) {
            [void](Pinned $criteria.$name.path $criteria.$name.sha256 "plan-input:$name")
        }
        Check 'execution-kind' ($null -ne $record.kind) ($record.kind -ceq 'EXECUTION')
        Check 'execution-complete' ($null -ne $record.complete) ($record.complete -is [bool] -and $record.complete)
        Check 'frozen-before-execution' ($null -ne $record.timeline) ($criteria.state -ceq 'FROZEN' -and
            (Timestamp $criteria.frozenAt) -lt (Timestamp $record.timeline[0].at))
        Check 'same-plan' ($null -ne $record.planDigest) ($record.planDigest -ceq $PlanDigest)
        Check 'rpo' ($null -ne $record.rpoMillis) (($record.rpoMillis -is [long] -or $record.rpoMillis -is [int]) -and $record.rpoMillis -ge 0 -and $record.rpoMillis -le $criteria.rpoMillis)
        Check 'rto' ($null -ne $record.rtoMillis) (($record.rtoMillis -is [long] -or $record.rtoMillis -is [int]) -and $record.rtoMillis -ge 0 -and $record.rtoMillis -le $criteria.rtoMillis)
        $root = Split-Path $Result -Parent
        $manifestPath = Join-Path (Split-Path $root -Parent) 'manifest.json'
        [void](Pinned (Join-Path (Split-Path $root -Parent) 'raw.json') $record.supportRawSha256 'support-workload')
        $recoveryPath = Join-Path (Split-Path $root -Parent) 'recovery.json'
        if (Pinned $recoveryPath $record.recoveryRawSha256 'source-recovery-fixture') {
            $sourceRecovery = Get-Content -LiteralPath $recoveryPath -Raw | ConvertFrom-Json
            Check 'bound-recovery-fixture' ($null -ne $sourceRecovery.complete) ($sourceRecovery.complete -eq $true -and $sourceRecovery.planDigest -ceq $criteria.recoveryPlan.sha256)
        }
        if (Pinned $manifestPath $record.sourceManifestSha256 'source-manifest') {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            $size = $manifest.data.initialReservations
            Check 'declared-support-point' ($null -ne $size) ($manifest.data.seed -eq 18101 -and
                $manifest.settings.'spring.datasource.hikari.maximum-pool-size' -eq 4 -and
                $manifest.data.slots -eq (2 * $size) -and $manifest.data.documents -eq $size -and
                (($size -eq 16 -and $manifest.targetRate -eq 20) -or ($size -eq 64 -and $manifest.targetRate -eq 5)))
            foreach ($field in $criteria.sourceDigests.PSObject.Properties) {
                Check "source:$($field.Name)" ($null -ne $manifest.sourceDigests.($field.Name)) ($manifest.sourceDigests.($field.Name) -ceq $field.Value)
            }
        }
        $mandatory = @('database.sql','backup-identity.json','snapshot.json','restored-before-reconcile.json',
            'security-authority.json','security-authority-identity.json','security-reconciled-snapshot.json',
            'oracle-before-security.json','oracle-after-service.json','previousBinary-identity.json','candidateBinary-identity.json',
            'previousBinary-config.json','candidateBinary-config.json','previousBinary-process.log','candidateBinary-process.log',
            'previousBinary-quiet.json','candidateBinary-quiet.json','final-snapshot.json','preserved-history.json')
        foreach ($name in $mandatory + 'deployment-contract.json') { Check "artifact-index:$name" ($null -ne $record.artifactDigests.$name) ($record.artifactDigests.$name -match '^[a-f0-9]{64}$') }
        Check 'frozen-deployment' ($null -ne $criteria.deploymentConfigSha256) ($criteria.deploymentConfigSha256 -ceq $record.artifactDigests.'deployment-contract.json')
        $trusted = @{}
        foreach ($entry in $record.artifactDigests.PSObject.Properties) {
            if ([IO.Path]::GetFileName($entry.Name) -cne $entry.Name) { Check 'artifact-path' $true $false; continue }
            $trusted[$entry.Name] = Pinned (Join-Path $root $entry.Name) $entry.Value "artifact:$($entry.Name)"
        }
        foreach ($gate in @('backup','emptyTarget','sourceStopped','sameRecoveryPoint','schema','binary','configuration','credentials','securityAuthority','securityReconciled','databaseOracle')) {
            Check "admission:$gate" ($null -ne $record.checks.$gate) ($record.checks.$gate -ceq 'PASS')
        }
        $events = @{}
        foreach ($event in $record.timeline) {
            if ($events.ContainsKey($event.name)) { Check "duplicate-event:$($event.name)" $true $false }
            $events[$event.name] = $event
        }
        foreach ($event in @('started','original-writers-stopped','quiesced-write','quiesced-ddl','backup-complete',
            'post-backup-commit','recovery-start','restored','actual-data-loss','stale-snapshot-risk-observed','security-reconciled',
            'append-only-update','append-only-delete','insufficient-backup-privilege','insufficient-restore-privilege',
            're-revocation-permission-denied','missing-credential','corrupt-checksum','missing-backup',
            'partial-receipt-negative','receipt-done-negative','receipt-effect-negative','resume-admitted',
            'binary-resumed-previousBinary','binary-resumed-candidateBinary',
            'post-backup-expiry','verified-service-live','all-consumers-converged-previousBinary','all-consumers-converged-candidateBinary')) {
            Check "required-execution:$event" ($events.ContainsKey($event)) ($events.ContainsKey($event))
        }
        foreach ($key in @('previousBinary','candidateBinary')) {
            $ready = $events["binary-resumed-$key"].details
            Check "ready:$key" ($null -ne $ready.readyMillis) ($ready.readyMillis -ge 0 -and $ready.readyMillis -le $criteria.restartReadyMillis)
            Check "binary:$key" ($null -ne $ready.sha256) ($ready.sha256 -ceq $criteria.$key.sha256)
            Check "executed-config:$key" ($null -ne $ready.configSha256) ($ready.configSha256 -ceq $record.artifactDigests."$key-config.json")
            $converged = $events["all-consumers-converged-$key"].details.millisFromProductLaunch
            Check "converged:$key" ($null -ne $converged) ($converged -ge 0 -and $converged -le $criteria.durableConvergenceMillis)
            if ($trusted["$key-quiet.json"]) {
                $quiet = Get-Content -LiteralPath (Join-Path $root "$key-quiet.json") -Raw | ConvertFrom-Json
                Check "quiet:$key" ($null -ne $quiet.elapsedMillis) ($quiet.elapsedMillis -le $criteria.postRecoveryQuietMillis -and
                    @($quiet.samples).Count -ge 3 -and @($quiet.samples | Select-Object -Last 3 | Where-Object { $_.active -ne 0 -or $_.pending -ne 0 }).Count -eq 0)
            }
        }
        if ($trusted['backup-identity.json'] -and $trusted['security-authority-identity.json']) {
            $backup = Get-Content -LiteralPath (Join-Path $root 'backup-identity.json') -Raw | ConvertFrom-Json
            $authority = Get-Content -LiteralPath (Join-Path $root 'security-authority-identity.json') -Raw | ConvertFrom-Json
            $source = $events['started'].details
            $target = $events['restored'].details
            Check 'independent-server' ($null -ne $target.serverUuid -and $null -ne $source.sourceServerUuid) ($target.serverUuid -cne $source.sourceServerUuid -and $target.targetContainer -cne $source.sourceContainer)
            Check 'original-backup' ($null -ne $backup.runId) ($backup.runId -ceq $record.runId -and $backup.sourceServerUuid -ceq $source.sourceServerUuid -and $backup.dumpSha256 -ceq $record.artifactDigests.'database.sql' -and $target.dumpSha256 -ceq $backup.dumpSha256)
            Check 'current-security-authority' ($null -ne $authority.runId) ($authority.runId -ceq $record.runId -and $authority.recoveryPoint -eq $backup.recoveryPoint -and $authority.sha256 -ceq $record.artifactDigests.'security-authority.json')
            $observedRpo = ((Timestamp $authority.sourceCutoff) - (Timestamp $backup.recoveryPoint)).TotalMilliseconds
            Check 'measured-rpo' ($null -ne $record.rpoMillis) ($observedRpo -ge 0 -and [Math]::Abs($observedRpo - $record.rpoMillis) -le 1)
            Check 'measured-live-rto' ($null -ne $record.rtoMillis -and $null -ne $events['verified-service-live'].details.rtoMillis) ($events['verified-service-live'].details.rtoMillis -eq $record.rtoMillis)
            $candidate = $events['all-consumers-converged-candidateBinary']
            $previous = $events['binary-resumed-previousBinary']
            Check 'candidate-to-previous-on-current-schema' ($null -ne $candidate -and $null -ne $previous) ((Timestamp $candidate.at) -lt (Timestamp $previous.at) -and
                $events['verified-service-live'].details.binary -ceq 'previousBinary' -and $events['verified-service-live'].details.schemaHistoryUnchanged -eq $true)
        }
        foreach ($name in @('oracle-before-security.json','oracle-after-service.json')) {
            if ($trusted[$name]) {
                $oracle = Get-Content -LiteralPath (Join-Path $root $name) -Raw | ConvertFrom-Json
                Check "oracle-complete:$name" (@($oracle.PSObject.Properties).Count -ge 60) (@($oracle.PSObject.Properties | Where-Object { $_.Value -ne 0 }).Count -eq 0)
            }
        }
        if ($trusted['preserved-history.json']) {
            $history = Get-Content -LiteralPath (Join-Path $root 'preserved-history.json') -Raw | ConvertFrom-Json
            Check 'original-history-preserved' (@($history.PSObject.Properties).Count -eq 12) (@($history.PSObject.Properties | Where-Object { $_.Value -ne 0 }).Count -eq 0)
        }
        if ($trusted['snapshot.json'] -and $trusted['restored-before-reconcile.json']) {
            $before = Get-Content -LiteralPath (Join-Path $root 'snapshot.json') -Raw | ConvertFrom-Json
            $after = Get-Content -LiteralPath (Join-Path $root 'restored-before-reconcile.json') -Raw | ConvertFrom-Json
            Check 'all-table-set' (@($before.PSObject.Properties).Count -ge 30) (@($before.PSObject.Properties).Count -eq @($after.PSObject.Properties).Count)
            foreach ($table in $before.PSObject.Properties) {
                $a = $after.($table.Name)
                Check "restored-values:$($table.Name)" ($null -ne $a) ($a.count -eq $table.Value.count -and
                    @($a.rows).Count -eq @($table.Value.rows).Count -and ([string]::Join("`n", $a.rows) -ceq [string]::Join("`n", $table.Value.rows)))
            }
        }
    }
} catch {
    Check 'readable-complete-evidence' $false $false
}
$status = if (@($checks | Where-Object status -eq 'FAIL').Count) { 'FAIL' }
    elseif (@($checks | Where-Object status -eq 'INCOMPLETE').Count) { 'INCOMPLETE' } else { 'PASS' }
[ordered]@{ kind='EVIDENCE_REVIEW'; reviewedAt=[DateTime]::UtcNow.ToString('o'); status=$status;
    executionId=$record.runId; resultDigest=$ResultDigest; planDigest=$PlanDigest;
    verifierDigest=(Hash $PSCommandPath); checks=$checks } | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $Output -Encoding utf8NoBOM
Write-Output "$status ($($checks.Count) checks)"
if ($status -ne 'PASS') { exit 1 }
