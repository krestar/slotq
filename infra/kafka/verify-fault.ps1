param([string]$OutputPath = (Join-Path $PSScriptRoot '../../docs/experiments/kafka-relay/2026-09-28-blocker-regression/cluster-raw.txt'))

$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.fault.yml'
$container = 'slotq-kafka-fault-kafka-1-1'
$bootstrap = 'kafka-1:19092'
$topic = 'slotq.waitlist.events.v1'
$runId = [guid]::NewGuid().ToString('N')
$probe = "slotq-fault:$runId"
$allProbe = "${probe}:all-up"
$oneProbe = "${probe}:one-down"
$twoProbe = "${probe}:two-down"
$lines = [Collections.Generic.List[string]]::new()

function Capture([string]$label, [object[]]$content) {
    $lines.Add("### $label $(Get-Date -Format o)")
    foreach ($item in $content) { $lines.Add(([string]$item).TrimEnd()) }
}
function Describe {
    $result = @(& docker exec $container /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server $bootstrap --describe --topic $topic 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Kafka describe failed' }
    return $result
}
function Assert-Topic([object[]]$description, [int]$expectedIsr) {
    $header = ($description | Where-Object { $_ -match 'PartitionCount:' }) -join ' '
    if ($header -notmatch 'PartitionCount:\s+3' -or $header -notmatch 'ReplicationFactor:\s+3' `
        -or $header -notmatch 'min.insync.replicas=2' `
        -or $header -notmatch 'unclean.leader.election.enable=false' `
        -or $header -notmatch 'retention.ms=86400000') {
        throw 'Fault topic configuration does not match RF=3/min ISR=2/unclean off'
    }
    $partitions = @($description | Where-Object { $_ -match 'Partition:\s+[0-9]+' })
    if ($partitions.Count -ne 3) { throw 'Fault topic must have three partitions' }
    foreach ($line in $partitions) {
        $replicas = [regex]::Match($line, 'Replicas:\s+([0-9,]+)').Groups[1].Value.Split(',')
        $isr = [regex]::Match($line, 'Isr:\s+([0-9,]+)').Groups[1].Value.Split(',')
        if ($replicas.Count -ne 3 -or @($replicas | Select-Object -Unique).Count -ne 3 `
            -or $replicas[0] -ne '1' -or $isr.Count -ne $expectedIsr) {
            throw "Fault topic replica/ISR mismatch: $line"
        }
    }
}
function Wait-Isr([int]$expectedIsr) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $description = @(Describe)
            Assert-Topic $description $expectedIsr
            return $description
        } catch { Start-Sleep -Seconds 1 }
    }
    throw "Fault topic did not reach ISR=$expectedIsr"
}
function Produce([string]$value) {
    $output = @($value | & docker exec -i $container /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server $bootstrap --topic $topic `
        --producer-property acks=all --producer-property enable.idempotence=true `
        --producer-property delivery.timeout.ms=10000 2>&1)
    return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
}

Capture 'current run identity' @($runId, $allProbe, $oneProbe, $twoProbe)
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $created = @(& docker exec $container /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server $bootstrap --create --if-not-exists --topic $topic `
        --replica-assignment 1:2:3,1:3:2,1:2:3 `
        --config min.insync.replicas=2 --config unclean.leader.election.enable=false `
        --config retention.ms=86400000 2>&1)
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds 1
}
if ($LASTEXITCODE -ne 0) { throw 'Fault topic creation failed' }
Capture 'topic preparation' $created
Capture 'all brokers' @(Wait-Isr 3)
$all = Produce $allProbe
Capture 'all brokers produce' $all.Output
if ($all.ExitCode -ne 0 -or ($all.Output | Out-String) -match 'ERROR|Exception') {
    throw 'All-up publication failed'
}

& docker compose -f $compose stop kafka-3 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to stop broker 3' }
try {
    Capture 'one broker down' @(Wait-Isr 2)
    $one = Produce $oneProbe
    Capture 'one broker down produce' $one.Output
    if ($one.ExitCode -ne 0 -or ($one.Output | Out-String) -match 'ERROR|Exception') {
        throw 'One-down publication failed'
    }
    & docker compose -f $compose stop kafka-2 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stop broker 2' }
    try {
        Capture 'two brokers down' @(& docker compose -f $compose ps)
        $two = Produce $twoProbe
        Capture 'two brokers down produce' $two.Output
        # kafka-console-producer may return 0 despite its failed send callback.
        if (($two.Output | Out-String) -notmatch 'NOT_ENOUGH_REPLICAS') {
            throw 'Expected min-ISR rejection'
        }
    } finally { & docker compose -f $compose start kafka-3 | Out-Null }
} finally { & docker compose -f $compose start kafka-2 | Out-Null }

Capture 'recovered cluster' @(Wait-Isr 3)
$records = @(& docker exec $container /opt/kafka/bin/kafka-console-consumer.sh `
    --bootstrap-server $bootstrap --topic $topic --from-beginning --timeout-ms 5000 2>&1)
Capture 'recovered broker records' $records
$current = @($records | Where-Object { [string]$_ -like "${probe}:*" })
$allCount = @($current | Where-Object { $_ -eq $allProbe }).Count
$oneCount = @($current | Where-Object { $_ -eq $oneProbe }).Count
$twoCount = @($current | Where-Object { $_ -eq $twoProbe }).Count
Capture 'current run record counts' @("all-up=$allCount", "one-down=$oneCount", "two-down=$twoCount")
$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $parent -Force | Out-Null
Set-Content -LiteralPath $OutputPath -Value $lines
if ($allCount -lt 1 -or $oneCount -lt 1 -or $twoCount -ne 0) {
    throw 'Current-run broker log does not match acknowledged and rejected probes'
}
Write-Output "Kafka fault evidence: $OutputPath (run $runId)"
