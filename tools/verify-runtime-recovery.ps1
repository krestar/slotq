param(
    [Parameter(Mandatory)][string]$EvidenceRoot,
    [Parameter(Mandatory)][string]$Plan,
    [Parameter(Mandatory)][string]$PlanDigest,
    [Parameter(Mandatory)][string]$Output,
    [string]$ProcessIndexDigest
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Output) { throw '판정 파일은 새 경로를 사용해야 합니다.' }
if ((Get-FileHash -LiteralPath $Plan -Algorithm SHA256).Hash -ine $PlanDigest) { throw '동결 기준 digest 불일치' }
$protocol = Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json
if ($protocol.state -ne 'FROZEN') { throw '동결 기준 누락' }
$checks = [Collections.Generic.List[object]]::new()
$hashes = [ordered]@{}
function Check([string]$Id, [bool]$Observed, [bool]$Valid, $Detail) {
    $status = if (!$Observed) { 'INCOMPLETE' } elseif (!$Valid) { 'FAIL' } else { 'PASS' }
    $checks.Add([ordered]@{ id=$Id; status=$status; detail=$Detail })
}
function Read-Evidence([string]$Relative) {
    $path = Join-Path $EvidenceRoot $Relative
    if (!(Test-Path -LiteralPath $path)) { return $null }
    $hashes[$Relative] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}
function Phase($Run, [string]$Name) { return @($Run.timeline | Where-Object phase -eq $Name) | Select-Object -Last 1 }
function Milliseconds($Start, $End) {
    if ($null -eq $Start.nanos -or $null -eq $End.nanos) { return $null }
    return ($End.nanos - $Start.nanos) / 1000000.0
}
$processIndex = Read-Evidence 'process-index.json'
$indexObserved = $null -ne $processIndex -and $ProcessIndexDigest -match '^[a-fA-F0-9]{64}$'
$indexValid = $indexObserved -and $hashes['process-index.json'] -ieq $ProcessIndexDigest -and
    $processIndex.schemaVersion -eq 'slotq-m8-recovery-process-index/v1' -and $processIndex.planDigest -ieq $PlanDigest
Check 'process-index' $indexObserved $indexValid '별도로 보관한 process index digest와 원본 report/checkpoint 연결'
function Evidence-Time($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { return [DateTimeOffset][DateTime]::SpecifyKind($Value, [DateTimeKind]::Utc) }
        return [DateTimeOffset]$Value
    }
    $parsed = [DateTimeOffset]::MinValue
    # Waitlist's UTC_TIMESTAMP(6) has no offset. ConvertFrom-Json can also return an already typed UTC DateTime.
    if ([DateTimeOffset]::TryParse([string]$Value, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)) { return $parsed }
    return $null
}
function Process-Identity([string]$Kind, $Report, [string]$Relative, [string]$Schema) {
    $entry = $processIndex.reports.$Kind
    $env = $Report.environment
    $at = Evidence-Time $env.recordedAt
    $observed = $null -ne $Report -and $null -ne $entry -and $null -ne $at -and
        ![string]::IsNullOrWhiteSpace($env.runId) -and ![string]::IsNullOrWhiteSpace($env.applicationRevision) -and $indexObserved
    $valid = $observed -and $indexValid -and $Report.schemaVersion -eq $Schema -and
        $entry.reportSha256 -ieq $hashes[$Relative] -and $entry.runId -ceq $env.runId -and
        $entry.applicationRevision -ceq $env.applicationRevision -and $entry.recordedAt -ceq $env.recordedAt -and
        $at -gt [DateTimeOffset]$protocol.frozenAt -and $at -le [DateTimeOffset]::UtcNow
    foreach ($case in $Report.cases) {
        $first = Evidence-Time $case.firstAuthoritativeState.databaseNow
        $last = Evidence-Time $case.recoveryAuthoritativeState.databaseNow
        if ($null -eq $first -or $null -eq $last) { $observed = $false; continue }
        $valid = $valid -and $first -gt [DateTimeOffset]$protocol.frozenAt -and $last -ge $first -and $last -le [DateTimeOffset]::UtcNow
        # Event metadata is recorded at completion; Waitlist metadata is recorded before its cases.
        if ($Kind -eq 'events') { $valid = $valid -and $last -le $at } else { $valid = $valid -and $first -ge $at }
    }
    Check "$Kind-identity" $observed $valid '고정한 원본 hash/run/revision/시각 및 FROZEN 이후 독립 DB 관측; 과거 report 재사용 차단'
    $digests = @($env.sourceDigests.PSObject.Properties)
    $sourceObserved = $null -ne $env.sourceDigests -and $digests.Count -gt 0
    $sourceValid = $sourceObserved
    $backendRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'backend'
    $expected = @(Get-ChildItem (Join-Path $backendRoot 'src') -File -Recurse | ForEach-Object { [IO.Path]::GetRelativePath($backendRoot, $_.FullName) }) + @('build.gradle')
    $sourceValid = $sourceValid -and $digests.Count -eq $expected.Count
    foreach ($path in $expected) {
        $sourceValid = $sourceValid -and $null -ne $env.sourceDigests.$path -and
            (Get-FileHash -LiteralPath (Join-Path $backendRoot $path)).Hash -ieq $env.sourceDigests.$path
    }
    Check "$Kind-source" $sourceObserved $sourceValid '실행 당시 전체 Backend source/resource/build digest와 현재 내용 대조'
}
function Restart-Ready([string]$Id, $Rows, [int]$Minimum=1) {
    $rowsArray = @($Rows | Where-Object { $null -ne $_ })
    $observed = $rowsArray.Count -ge $Minimum -and $null -ne $protocol.gates.restartReadyMillis
    $valid = $true
    $durations = @()
    foreach ($row in $rowsArray) {
        $launch = Evidence-Time $row.launchedAt; $ready = Evidence-Time $row.observedAt
        if ($null -eq $row.launchNanos -or $null -eq $row.readyObservedNanos -or $null -eq $launch -or $null -eq $ready -or
            $null -eq $row.clock -or $null -eq $row.parentPid -or $null -eq $row.childPid) {
            $observed = $false
            continue
        }
        $ms = ($row.readyObservedNanos - $row.launchNanos) / 1000000.0
        $durations += $ms
        $valid = $valid -and $row.clock -eq 'parent-System.nanoTime' -and $row.parentPid -gt 0 -and
            $row.childPid -gt 0 -and $row.childPid -ne $row.parentPid -and $ms -ge 0 -and $ms -le $protocol.gates.restartReadyMillis -and
            $launch -gt [DateTimeOffset]$protocol.frozenAt -and $ready -ge $launch -and $ready -le [DateTimeOffset]::UtcNow
    }
    Check $Id $observed $valid @{millis=$durations; meaning='동일 부모 monotonic clock의 launch 직전부터 ready checkpoint 관측까지; effect/DONE 수렴 시간과 별도'}
}
foreach ($size in @(16,64)) {
    $run = Read-Evidence "capacity/s$size/recovery.json"
    $manifest = Read-Evidence "capacity/s$size/manifest.json"
    if ($null -eq $run -or $null -eq $manifest) {
        Check "profile-$size" $false $false '실제 실행 또는 manifest 누락'
        continue
    }
    Check "profile-$size-complete" $true ($run.schemaVersion -eq 'slotq-m8-recovery/v1' -and $manifest.schemaVersion -eq 'slotq-m8-capacity/v2' -and $run.complete -eq $true -and $run.kind -eq 'EXECUTION' -and $run.planDigest -ieq $PlanDigest) '실제 실행과 사전 기준 연결'
    $start = Phase $run 'started'
    $rate = if ($size -eq 16) {20} else {5}
    Check "profile-$size-settings" $true ($start.supportPool -eq 4 -and $start.size -eq $size -and $start.rate -eq $rate -and $start.externalProviderCalls -eq 0 -and $manifest.data.seed -eq 18101 -and $manifest.data.slots -eq 2*$size -and $manifest.data.initialReservations -eq $size -and $manifest.data.documents -eq $size) 'pool/초기 데이터 규모/유입률/seed/합성 제공자'
    $sourceMatches = @($manifest.sourceDigests.PSObject.Properties).Count -gt 0
    $backend = Join-Path (Split-Path $PSScriptRoot -Parent) 'backend'
    foreach ($source in $manifest.sourceDigests.PSObject.Properties) {
        $file = Join-Path $backend $source.Name
        if (!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file).Hash -ine $source.Value) { $sourceMatches = $false }
    }
    Check "profile-$size-source" $true $sourceMatches '실행 source/resource/build digest와 현재 파일 대조'
    $raw = Read-Evidence "capacity/s$size/raw.json"
    $rawDigestFile = Join-Path $EvidenceRoot "capacity/s$size/raw.sha256"
    $rawObserved = $null -ne $raw -and (Test-Path -LiteralPath $rawDigestFile)
    $rawValid = $false
    if ($rawObserved) {
        $rawValid = $hashes["capacity/s$size/raw.json"] -ieq (Get-Content -LiteralPath $rawDigestFile -Raw).Trim()
        $rawValid = $rawValid -and $raw.oracle.independentPhysicalMySql -eq $true -and $raw.oracle.violations -eq 0
    }
    Check "profile-$size-independent-oracle" $rawObserved $rawValid '원자료 digest와 독립 DB의 멱등성/receipt/DONE/유한 재시도/참조 정합성'
    $samples = @($raw.samples | Where-Object phase -eq 'final-quiet')
    $consecutive = 0; $recovered = $false; $covered = $true; $previous = $null
    foreach ($sample in $samples) {
        if ($null -ne $previous -and ($sample.nanos - $previous) -gt 1000000000) { $covered = $false }
        $previous = $sample.nanos
        $idle = $sample.databaseObservation -eq 'OBSERVED'
        foreach ($key in @('active','pending','inFlight','mcpWorkers','runtimeWorkers','mcpPermits','runtimePermits','mcpIngress','backlog','unmaterialized')) {
            if ($null -eq $sample.$key) { $covered = $false; $idle = $false }
            elseif ($sample.$key -ne 0) { $idle = $false }
        }
        if ($idle) { $consecutive++ } else { $consecutive=0 }
        if ($consecutive -ge 3) { $recovered=$true }
    }
    Check "profile-$size-final-resources" ($samples.Count -ge 3 -and $covered) $recovered '최종 quiet 구간의 연속 3 idle sample; 관측 공백은 INCOMPLETE'
    $sql = Phase $run 'product-database-failure'
    $actual = @($sql.sqlFailures | Where-Object { $_.method -eq 'authenticateProduct' -and @($_.exceptionClasses | Where-Object { $_ -match 'mysql.*CommunicationsException|java.sql.' }).Count -gt 0 })
    $callerObserved = $sql.httpStatus -eq 'UNOBSERVED' -or ($sql.httpStatus -is [ValueType] -and $sql.httpStatus -ge 500)
    Check "profile-$size-product-db-outage" ($null -ne $sql) ($actual.Count -gt 0 -and $callerObserved) 'Product Auth 실제 JDBC 실패와 별도 호출자 관측; timeout은 UNKNOWN 유지'
    foreach ($name in @('SlotqDatabaseSampleUnavailable','SlotqDeadDelivery')) {
        $alerts = @($run.timeline | Where-Object { $_.phase -eq 'alert-transition' -and $_.alert -eq $name })
        $firing = @($alerts | Where-Object firing -eq $true)
        $cleared = @($alerts | Where-Object firing -eq $false)
        Check "profile-$size-$name" ($firing.Count -gt 0 -and $cleared.Count -gt 0) ($firing.Count -eq 1 -and $cleared.Count -gt 0 -and [DateTimeOffset]$cleared[-1].at -gt [DateTimeOffset]$firing[0].at) '실제 Prometheus 발생과 후속 해제'
    }
    $pause = Phase $run 'database-paused'
    $detect = @($run.timeline | Where-Object { $_.phase -eq 'alert-transition' -and $_.alert -eq 'SlotqDatabaseSampleUnavailable' -and $_.firing }) | Select-Object -First 1
    $observed = $null -ne $pause -and $null -ne $detect
    $ms = if ($observed) { Milliseconds $pause $detect } else { $null }
    $observed = $observed -and $null -ne $ms
    Check "profile-$size-detection-time" $observed ($observed -and $ms -ge 0 -and $ms -le $protocol.gates.detectionMillis) $ms
    foreach ($pair in @(@('database-unpaused','database-converged'),@('operator-recovery-started','operator-recovery-converged'))) {
        $begin = Phase $run $pair[0]; $end = Phase $run $pair[1]
        $observed = $null -ne $begin -and $null -ne $end
        $ms = if ($observed) { Milliseconds $begin $end } else { $null }
        $observed = $observed -and $null -ne $ms
        Check "profile-$size-$($pair[1])-time" $observed ($observed -and $ms -ge 0 -and $ms -le $protocol.gates.durableConvergenceMillis) $ms
    }
    $final = Phase $run 'final-durable-oracle'
    $valid = $null -ne $final
    foreach ($key in @('partialReceipts','duplicateLogicalEffects','occupancyViolations','dead')) { $valid = $valid -and $final.database.stats.$key -eq 0 }
    Check "profile-$size-durable" ($null -ne $final) $valid '독립 MySQL 최종 불변식; HTTP 성공과 분리'
}
$events = Read-Evidence 'events/raw/report.json'
Process-Identity 'events' $events 'events/raw/report.json' 'slotq-event-process-recovery/v1'
Restart-Ready 'events-restart-ready' $events.processReadiness 8
foreach ($scenario in @('materialization','claim','effect-crash','commit-unknown','stale-owner','exhaustion','db-unavailable','backlog')) {
    $rows = @($events.processReadiness | Where-Object { $_.scenario -eq $scenario -and $_.command -in @('DRAIN','PROCESS_ONE','REPLAY_DRAIN') })
    Check "events-$scenario-readiness-coverage" ($rows.Count -gt 0) ($rows.Count -gt 0) '필수 시나리오의 실제 child 준비 관측'
}
$expectedEvents = @('MATERIALIZATION_BEFORE_PROCESS_EXIT','CLAIM_COMMIT_THEN_PROCESS_EXIT','EFFECT_TRANSACTION_PROCESS_EXIT','EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN','STALE_OWNER_AFTER_NEW_OWNER_DONE','CLAIM_CRASH_EXHAUSTION','TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION','DATABASE_UNAVAILABLE_AND_RECOVERY','BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN')
foreach ($name in $expectedEvents) {
    $case = @($events.cases | Where-Object name -eq $name)
    $valid = $case.Count -eq 1 -and $case[0].outcome -eq 'PASS'
    if ($valid) {
        $window = ([DateTimeOffset]$case[0].recoveryAuthoritativeState.databaseNow - [DateTimeOffset]$case[0].firstAuthoritativeState.databaseNow).TotalMilliseconds
        $valid = $window -ge 0 -and $window -le $protocol.gates.durableConvergenceMillis
    }
    Check "event-$name" ($case.Count -eq 1) $valid 'synthetic consumer protocol 회귀와 DB 수렴 시각; 개인 운영자 복구를 대체하지 않음'
}
$waitlist = Read-Evidence 'waitlist/report.json'
Process-Identity 'waitlist' $waitlist 'waitlist/report.json' 'slotq-waitlist-process-recovery/v1'
foreach ($name in @('RELEASE_CRASH','EFFECT_CRASH','COMMIT_CRASH','BACKLOG_SEED','OUTAGE_SEED')) {
    $case = @($waitlist.cases | Where-Object name -eq $name)
    Check "waitlist-$name" ($case.Count -eq 1) ($case.Count -eq 1 -and $case[0].outcome -eq 'PASS' -and $case[0].recoveryLaunchToObservationMillis -le $protocol.gates.durableConvergenceMillis) '실제 production scheduler 및 독립 DB oracle'
    Restart-Ready "waitlist-$name-restart-ready" $case.recoveryReadiness
}
$junit = Join-Path $EvidenceRoot 'junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml'
if (Test-Path -LiteralPath $junit) {
    $hashes['junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml'] = (Get-FileHash -LiteralPath $junit).Hash.ToLowerInvariant()
    [xml]$xml = Get-Content -LiteralPath $junit -Raw
    Check 'agent-junit-fresh' $true ([DateTimeOffset]$xml.testsuite.timestamp -gt [DateTimeOffset]$protocol.frozenAt) '사전 기준 이후 실행한 JUnit'
    foreach ($name in @('lifetimeForcedProcessTerminationKeepsProductAuthorityAndRequiresExplicitRecovery','lifetimeRestartRevalidatesRevokedAuthorityBeforeKnownTargetGet','lifetimeCancellationBeforeDispatchPreventsMutation','cancellationKeepsContinuingProductWorkAndLateEffectWithoutNewDispatch','genericTimeoutCancellationAndLateProductSuccessPreservePhysicalWorkAndEffect')) {
        $cases = if ($name -eq 'lifetimeForcedProcessTerminationKeepsProductAuthorityAndRequiresExplicitRecovery') {
            @($xml.testsuite.testcase | Where-Object { $_.name -match '^\[[1-4]\] point = (BEFORE_DISPATCH|IN_PRODUCT_BEFORE_COMMIT|AFTER_COMMIT|AFTER_RESPONSE_LOSS)$' })
        } else { @($xml.testsuite.testcase | Where-Object { $_.name.StartsWith($name) }) }
        $expected = if ($name -eq 'lifetimeForcedProcessTerminationKeepsProductAuthorityAndRequiresExplicitRecovery') {4} else {1}
        $valid = $cases.Count -eq $expected -and @($cases | Where-Object { $_.failure -or $_.error -or $_.skipped }).Count -eq 0
        Check "agent-$name" ($cases.Count -gt 0) $valid '실제 child 종료, 별도 DB 조회 또는 명시적 cancellation/late outcome 회귀'
    }
} else { Check 'agent-junit' $false $false 'fresh JUnit 누락' }
$agentEntries = @($processIndex.agent.cases | Where-Object { $null -ne $_ })
Check 'agent-process-index' ($indexObserved -and $agentEntries.Count -gt 0) ($indexValid -and $agentEntries.Count -eq 5 -and $processIndex.agent.junitSha256 -ieq $hashes['junit/TEST-com.slotq.mcp.RepresentativeFlowIntegrationTests.xml']) 'fresh JUnit과 5개 실제 재기동 checkpoint 원본 hash 연결'
$agentNames = @()
$suiteStart = Evidence-Time $xml.testsuite.timestamp
$suiteEnd = if ($null -ne $suiteStart -and $null -ne $xml.testsuite.time) { $suiteStart.AddSeconds([double]$xml.testsuite.time) } else { $null }
foreach ($entry in $agentEntries) {
    $folder = $entry.directory
    if ($folder -notmatch '^agent/[a-zA-Z0-9_-]+$') { Check 'agent-checkpoint-path' $true $false $folder; continue }
    $summary = Read-Evidence "$folder/summary.json"
    $prepared = Read-Evidence "$folder/original/prepared.json"
    $restarted = Read-Evidence "$folder/restart/restarted.json"
    $readiness = Read-Evidence "$folder/restart/readiness.json"
    $name = if ($summary.explicitRecovery -eq 'denied-current-authority') {'REVOKED_AUTHORITY'} else {$summary.killPoint}
    $agentNames += $name
    $observed = $indexObserved -and $null -ne $summary -and $null -ne $prepared -and $null -ne $restarted -and $null -ne $readiness -and $null -ne $suiteEnd
    $valid = $indexValid -and $entry.summarySha256 -ieq $hashes["$folder/summary.json"] -and
        $entry.preparedSha256 -ieq $hashes["$folder/original/prepared.json"] -and $entry.restartedSha256 -ieq $hashes["$folder/restart/restarted.json"] -and
        $entry.readinessSha256 -ieq $hashes["$folder/restart/readiness.json"] -and $summary.automaticMutationAfterRestart -eq $false -and
        $restarted.oldRunRejected -eq $true -and $restarted.workers -eq 0 -and $prepared.pid -eq $summary.killedPid -and
        $restarted.pid -eq $readiness.childPid -and $restarted.pid -ne $summary.killedPid -and
        (Evidence-Time $prepared.at) -gt [DateTimeOffset]$protocol.frozenAt -and (Evidence-Time $prepared.at) -ge $suiteStart -and
        (Evidence-Time $restarted.at) -ge (Evidence-Time $prepared.at) -and (Evidence-Time $readiness.observedAt) -le $suiteEnd -and
        (Evidence-Time $readiness.launchedAt) -ge (Evidence-Time $prepared.at) -and (Evidence-Time $readiness.observedAt) -ge (Evidence-Time $restarted.at)
    Check "agent-$name-checkpoint-identity" $observed $valid '원본 prepared/restarted/summary/readiness와 종료·재시작 PID 연결'
    Restart-Ready "agent-$name-restart-ready" $readiness
}
foreach ($name in @('BEFORE_DISPATCH','IN_PRODUCT_BEFORE_COMMIT','AFTER_COMMIT','AFTER_RESPONSE_LOSS','REVOKED_AUTHORITY')) {
    $count = @($agentNames | Where-Object { $_ -eq $name }).Count
    Check "agent-$name-readiness-coverage" ($count -gt 0) ($count -eq 1) '필수 실제 재기동 case의 중복·누락 검사'
}
$status = if (@($checks | Where-Object status -eq 'FAIL').Count) {'FAIL'} elseif (@($checks | Where-Object status -eq 'INCOMPLETE').Count) {'INCOMPLETE'} else {'PASS'}
[ordered]@{
    schemaVersion='slotq-m8-recovery-review/v1'
    kind='EVIDENCE_REVIEW'
    status=$status
    planDigest=$PlanDigest
    processIndexDigest=$ProcessIndexDigest
    executionIdentity=$processIndex.reports
    agentJUnitTimestamp=$xml.testsuite.timestamp
    reviewerSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    checks=$checks
    sha256=$hashes
    reviewedAt=[DateTimeOffset]::UtcNow.ToString('o')
    scope='기존 실행 자료의 대조이며 새 장애 실험이 아님; Issue #182 단일 호스트 DB_DIRECT 범위; #183 복원 및 #185 GO 판정 제외'
} | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Output -Encoding utf8
Write-Output "복구 검증 자료 대조: $status"
if ($status -ne 'PASS') { exit 1 }
