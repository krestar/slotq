param([string]$OutputRoot = (Join-Path $PSScriptRoot '../../build/runtime-recovery-verifier-tests'))
$ErrorActionPreference = 'Stop'
$root = Join-Path $OutputRoot ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $root -Force | Out-Null
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$verifier = Join-Path $repo 'tools/verify-runtime-recovery.ps1'
function Write-Json($Path, $Value) {
    New-Item -ItemType Directory -Path (Split-Path $Path) -Force | Out-Null
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding utf8
}
function Hash($Path) { (Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant() }
$plan = Join-Path $root 'plan.json'
Write-Json $plan @{state='FROZEN'; frozenAt='2026-01-01T23:59:00Z'; gates=@{restartReadyMillis=60000; durableConvergenceMillis=90000}}
$planDigest = Hash $plan
$sources = [ordered]@{}
Get-ChildItem (Join-Path $repo 'backend/src') -Recurse -File | ForEach-Object {
    $sources[[IO.Path]::GetRelativePath((Join-Path $repo 'backend'), $_.FullName)] = Hash $_.FullName
}
$sources['build.gradle'] = Hash (Join-Path $repo 'backend/build.gradle')
$eventNames = @('MATERIALIZATION_BEFORE_PROCESS_EXIT','CLAIM_COMMIT_THEN_PROCESS_EXIT','EFFECT_TRANSACTION_PROCESS_EXIT','EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN','STALE_OWNER_AFTER_NEW_OWNER_DONE','CLAIM_CRASH_EXHAUSTION','TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION','DATABASE_UNAVAILABLE_AND_RECOVERY','BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN')
$waitlistNames = @('RELEASE_CRASH','EFFECT_CRASH','COMMIT_CRASH','BACKLOG_SEED','OUTAGE_SEED')
$agentNames = @('BEFORE_DISPATCH','IN_PRODUCT_BEFORE_COMMIT','AFTER_COMMIT','AFTER_RESPONSE_LOSS','REVOKED_AUTHORITY')
function Ready {
    @{clock='parent-System.nanoTime'; parentPid=100; childPid=200; launchedAt='2026-01-02T00:00:00Z'; observedAt='2026-01-02T00:00:10Z'; launchNanos=1000000000L; readyObservedNanos=11000000000L}
}
function Fixture($Directory) {
    $env = @{runId='fixture-run'; applicationRevision='fixture-source'; recordedAt='2026-01-02T00:01:00Z'; sourceDigests=$sources}
    $events = @{schemaVersion='slotq-event-process-recovery/v1'; environment=$env.Clone(); cases=@($eventNames | ForEach-Object {
        @{name=$_; outcome='PASS'; firstAuthoritativeState=@{databaseNow='2026-01-02T00:00:00Z'}; recoveryAuthoritativeState=@{databaseNow='2026-01-02T00:00:20Z'}}
    }); processReadiness=@(@('materialization','claim','effect-crash','commit-unknown','stale-owner','exhaustion','db-unavailable','backlog') | ForEach-Object {
        $r=Ready; $r.scenario=$_; $r.command='DRAIN'; $r
    })}
    Write-Json "$Directory/events/raw/report.json" $events
    $waitlist = @{schemaVersion='slotq-waitlist-process-recovery/v1'; environment=$env.Clone(); cases=@($waitlistNames | ForEach-Object {
        @{name=$_; outcome='PASS'; recoveryReadiness=(Ready); recoveryLaunchToObservationMillis=20000;
          firstAuthoritativeState=@{databaseNow='2026-01-02 00:01:01'}; recoveryAuthoritativeState=@{databaseNow='2026-01-02 00:01:20'}}
    })}
    Write-Json "$Directory/waitlist/report.json" $waitlist
    New-Item -ItemType Directory -Path "$Directory/junit" -Force | Out-Null
    '<testsuite timestamp="2026-01-02T00:00:00Z" time="120"><testcase name="lifetimeRestartRevalidatesRevokedAuthorityBeforeKnownTargetGet()"/></testsuite>' |
        Set-Content "$Directory/junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml"
    foreach ($name in $agentNames) {
        $folder="$Directory/agent/$name"
        $point=if ($name -eq 'REVOKED_AUTHORITY') {'AFTER_RESPONSE_LOSS'} else {$name}
        Write-Json "$folder/summary.json" @{killPoint=$point; explicitRecovery=$(if ($name -eq 'REVOKED_AUTHORITY') {'denied-current-authority'} elseif ($name -eq 'AFTER_RESPONSE_LOSS') {'known-target-exact-get'} else {'unknown-target-no-dispatch'}); killedPid=150; automaticMutationAfterRestart=$false}
        Write-Json "$folder/original/prepared.json" @{at='2026-01-02T00:00:00Z'; pid=150}
        Write-Json "$folder/restart/restarted.json" @{at='2026-01-02T00:00:10Z'; pid=200; oldRunRejected=$true; workers=0}
        Write-Json "$folder/restart/readiness.json" (Ready)
    }
}
function Pin($Directory) {
    $entries=@{}
    foreach ($kind in @('events','waitlist')) {
        $relative=if ($kind -eq 'events') {'events/raw/report.json'} else {'waitlist/report.json'}
        $report=Get-Content "$Directory/$relative" -Raw | ConvertFrom-Json
        $entries[$kind]=@{reportSha256=(Hash "$Directory/$relative"); runId=$report.environment.runId; applicationRevision=$report.environment.applicationRevision; recordedAt=$report.environment.recordedAt}
    }
    $agents=@($agentNames | ForEach-Object {
        $folder="agent/$_"
        @{directory=$folder; summarySha256=(Hash "$Directory/$folder/summary.json"); preparedSha256=(Hash "$Directory/$folder/original/prepared.json"); restartedSha256=(Hash "$Directory/$folder/restart/restarted.json"); readinessSha256=(Hash "$Directory/$folder/restart/readiness.json")}
    })
    Write-Json "$Directory/process-index.json" @{schemaVersion='slotq-m8-recovery-process-index/v1'; planDigest=$planDigest; reports=$entries; agent=@{junitSha256=(Hash "$Directory/junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml"); cases=$agents}}
    Hash "$Directory/process-index.json"
}
$results=[Collections.Generic.List[object]]::new()
function Run-Case($Name, $Mutate, $ExpectedId, $ExpectedStatus, [bool]$Repin=$true) {
    $directory=Join-Path $root $Name
    Fixture $directory
    $digest=Pin $directory
    & $Mutate $directory
    if ($Repin) { $digest=Pin $directory }
    $output=Join-Path $directory 'review.json'
    # The old verifier has no index parameter; exercise it too so the regression proves the original omission.
    $arguments=@('-NoProfile','-File',$verifier,'-EvidenceRoot',$directory,'-Plan',$plan,'-PlanDigest',$planDigest,'-Output',$output)
    if ((Get-Command $verifier).Parameters.ContainsKey('ProcessIndexDigest')) { $arguments+=@('-ProcessIndexDigest',$digest) }
    & (Get-Process -Id $PID).Path @arguments | Out-Null
    $review=Get-Content $output -Raw | ConvertFrom-Json
    $check=@($review.checks | Where-Object id -eq $ExpectedId)
    $passed=$check.Count -eq 1 -and $check[0].status -eq $ExpectedStatus
    $passed=$passed -and $review.kind -eq 'EVIDENCE_REVIEW' -and $null -ne $review.reviewedAt
    if (Test-Path "$directory/process-index.json") { $passed=$passed -and $null -ne $review.executionIdentity }
    else { $passed=$passed -and $null -eq $review.executionIdentity }
    $results.Add(@{name=$Name; passed=$passed; expected="$ExpectedId=$ExpectedStatus"; actual=$(if ($check.Count) {$check[0].status} else {'CHECK_MISSING'})})
}
function Change-Report($Directory,$Relative,$Action) {
    $report=Get-Content "$Directory/$Relative" -Raw | ConvertFrom-Json -AsHashtable
    & $Action $report
    Write-Json "$Directory/$Relative" $report
}
Run-Case 'valid-event-identity' {} 'events-identity' 'PASS'
Run-Case 'valid-waitlist-identity' {} 'waitlist-identity' 'PASS'
Run-Case 'valid-source' {} 'events-source' 'PASS'
Run-Case 'stale-event' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.environment.recordedAt='2025-01-01T00:00:00Z'}} 'events-identity' 'FAIL'
Run-Case 'stale-waitlist' {param($d) Change-Report $d 'waitlist/report.json' {param($r) $r.environment.recordedAt='2025-01-01T00:00:00Z'}} 'waitlist-identity' 'FAIL'
Run-Case 'wrong-source' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.environment.sourceDigests['build.gradle']='0'*64}} 'events-source' 'FAIL'
Run-Case 'missing-source' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.environment.Remove('sourceDigests')}} 'events-source' 'INCOMPLETE'
Run-Case 'report-replaced' {param($d) Change-Report $d 'waitlist/report.json' {param($r) $r.environment.runId='other-run'}} 'waitlist-identity' 'FAIL' $false
Run-Case 'missing-run' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.environment.Remove('runId')}} 'events-identity' 'INCOMPLETE'
Run-Case 'missing-index' {param($d) Remove-Item -LiteralPath "$d/process-index.json"} 'process-index' 'INCOMPLETE' $false
Run-Case 'index-replaced' {param($d) Change-Report $d 'process-index.json' {param($r) $r.reports.events.runId='other-run'}} 'process-index' 'FAIL' $false
Run-Case 'event-ready-valid' {} 'events-restart-ready' 'PASS'
Run-Case 'event-ready-over' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.processReadiness[0].readyObservedNanos=61001000000L+$r.processReadiness[0].launchNanos}} 'events-restart-ready' 'FAIL'
Run-Case 'event-ready-missing' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.Remove('processReadiness')}} 'events-restart-ready' 'INCOMPLETE'
Run-Case 'event-wrong-clock' {param($d) Change-Report $d 'events/raw/report.json' {param($r) $r.processReadiness[0].clock='different-JVM-clocks'}} 'events-restart-ready' 'FAIL'
Run-Case 'waitlist-ready-over' {param($d) Change-Report $d 'waitlist/report.json' {param($r) $r.cases[0].recoveryReadiness.readyObservedNanos=61001000000L+$r.cases[0].recoveryReadiness.launchNanos}} 'waitlist-RELEASE_CRASH-restart-ready' 'FAIL'
Run-Case 'waitlist-converges-without-ready' {param($d) Change-Report $d 'waitlist/report.json' {param($r) $r.cases[0].Remove('recoveryReadiness')}} 'waitlist-RELEASE_CRASH-restart-ready' 'INCOMPLETE'
Run-Case 'waitlist-slow-convergence-ready-valid' {param($d) Change-Report $d 'waitlist/report.json' {param($r) $r.cases[0].recoveryLaunchToObservationMillis=85000}} 'waitlist-RELEASE_CRASH-restart-ready' 'PASS'
Run-Case 'agent-ready-valid' {} 'agent-BEFORE_DISPATCH-restart-ready' 'PASS'
Run-Case 'agent-ready-exact-bound' {param($d) Change-Report $d 'agent/BEFORE_DISPATCH/restart/readiness.json' {param($r) $r.readyObservedNanos=$r.launchNanos+60000000000L}} 'agent-BEFORE_DISPATCH-restart-ready' 'PASS'
Run-Case 'agent-ready-over' {param($d) Change-Report $d 'agent/BEFORE_DISPATCH/restart/readiness.json' {param($r) $r.readyObservedNanos=$r.launchNanos+60001000000L}} 'agent-BEFORE_DISPATCH-restart-ready' 'FAIL'
Run-Case 'agent-ready-missing' {param($d) Change-Report $d 'agent/BEFORE_DISPATCH/restart/readiness.json' {param($r) $r.Remove('launchNanos')}} 'agent-BEFORE_DISPATCH-restart-ready' 'INCOMPLETE'
Write-Json (Join-Path $root 'results.json') $results
$results | ForEach-Object { [pscustomobject]$_ } | Format-Table name,passed,expected,actual -AutoSize
Write-Output "회귀 결과: $root"
if (@($results | Where-Object { !$_.passed }).Count) { exit 1 }
