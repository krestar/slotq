param([string]$OutputPath = (Join-Path $PSScriptRoot '../../docs/experiments/kafka-relay/2026-09-28/cluster-raw.txt'))

$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.fault.yml'
$lines = [Collections.Generic.List[string]]::new()
function Capture([string]$label, [object[]]$content) {
    $lines.Add("### $label $(Get-Date -Format o)")
    foreach ($item in $content) { $lines.Add([string]$item) }
}
function Describe {
    $result = & docker exec slotq-kafka-fault-kafka-1-1 /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server kafka-1:19092 --describe --topic slotq.waitlist.events.v1 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Kafka describe failed' }
    return $result
}
function Produce([string]$text) {
    return $text | & docker exec -i slotq-kafka-fault-kafka-1-1 /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server kafka-1:19092 --topic slotq.waitlist.events.v1 `
        --producer-property acks=all --producer-property enable.idempotence=true `
        --producer-property delivery.timeout.ms=10000 2>&1
}
Capture 'all brokers' (Describe)
Capture 'all brokers produce' (Produce 'probe-all-up')
& docker compose -f $compose stop kafka-3 | Out-Null
try {
    Capture 'one broker down' (Describe)
    $one = Produce 'probe-one-down'
    Capture 'one broker down produce' $one
    if (($one | Out-String) -match 'NOT_ENOUGH_REPLICAS') { throw 'One failure should retain min ISR' }
    & docker compose -f $compose stop kafka-2 | Out-Null
    try {
        Capture 'two brokers down' (& docker compose -f $compose ps)
        $two = Produce 'probe-two-down'
        Capture 'two brokers down produce' $two
        # kafka-console-producer logs callback errors but may still exit with status 0.
        if (($two | Out-String) -notmatch 'NOT_ENOUGH_REPLICAS') { throw 'Expected min ISR rejection' }
    } finally { & docker compose -f $compose start kafka-2 | Out-Null }
} finally { & docker compose -f $compose start kafka-3 | Out-Null }
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    try { $recovered = Describe } catch { $recovered = @('metadata unavailable while quorum recovers') }
    if (($recovered | Out-String) -match 'Isr: 1,2,3|Isr: 1,3,2|Isr: 2,1,3|Isr: 2,3,1|Isr: 3,1,2|Isr: 3,2,1') { break }
    Start-Sleep -Seconds 1
}
Capture 'recovered cluster' $recovered
$records = & docker exec slotq-kafka-fault-kafka-1-1 /opt/kafka/bin/kafka-console-consumer.sh `
    --bootstrap-server kafka-1:19092 --topic slotq.waitlist.events.v1 --from-beginning `
    --timeout-ms 5000 2>&1
Capture 'recovered broker records' $records
$observed = $records | Out-String
if ($observed -notmatch 'probe-all-up' -or $observed -notmatch 'probe-one-down') {
    throw 'Expected both acknowledged probes in the recovered log'
}
if ($observed -match 'probe-two-down') { throw 'Rejected min-ISR probe appeared in the log' }
$parent = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $parent -Force | Out-Null
Set-Content -LiteralPath $OutputPath -Value $lines
Write-Output "Kafka fault evidence: $OutputPath"
