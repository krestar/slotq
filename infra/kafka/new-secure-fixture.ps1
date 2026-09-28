param([string]$OutputDirectory = (Join-Path $env:TEMP 'slotq-kafka-107-security'))

$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point to JDK 25' }
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK keytool not found' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$directory = (Resolve-Path -LiteralPath $OutputDirectory).Path
if ((Get-ChildItem -LiteralPath $directory -Force | Measure-Object).Count -gt 0) {
    throw 'Use an empty temporary directory for fresh credentials'
}
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant() }
$storePassword = New-Secret
$adminPassword = New-Secret
$relayPassword = New-Secret
$waitlistPassword = New-Secret
$monitorPassword = New-Secret

& $keytool -genkeypair -alias broker -keystore (Join-Path $directory 'broker.p12') -storetype PKCS12 `
    -storepass $storePassword -keypass $storePassword -dname 'CN=localhost' -validity 2 `
    -keyalg RSA -keysize 2048 -ext 'SAN=dns:localhost,ip:127.0.0.1' -noprompt | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'broker certificate generation failed' }
& $keytool -exportcert -alias broker -keystore (Join-Path $directory 'broker.p12') `
    -storepass $storePassword -rfc -file (Join-Path $directory 'broker.crt') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'certificate export failed' }
& $keytool -importcert -alias broker -file (Join-Path $directory 'broker.crt') `
    -keystore (Join-Path $directory 'truststore.p12') -storetype PKCS12 -storepass $storePassword -noprompt | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'truststore import failed' }
Set-Content -LiteralPath (Join-Path $directory 'broker.credentials') -NoNewline -Value $storePassword

$server = @"
KafkaServer {
  org.apache.kafka.common.security.plain.PlainLoginModule required
  username="admin" password="$adminPassword"
  user_admin="$adminPassword"
  user_relay="$relayPassword"
  user_waitlist="$waitlistPassword"
  user_monitor="$monitorPassword";
};
"@
Set-Content -LiteralPath (Join-Path $directory 'server_jaas.conf') -Value $server
@{admin=$adminPassword; relay=$relayPassword; waitlist=$waitlistPassword; monitor=$monitorPassword}.GetEnumerator() | ForEach-Object {
    $client = @"
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="$($_.Key)" password="$($_.Value)";
ssl.truststore.location=/etc/kafka/secrets/truststore.p12
ssl.truststore.password=$storePassword
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https
"@
    Set-Content -LiteralPath (Join-Path $directory "client-$($_.Key).properties") -Value $client
}
Write-Output "Generated ephemeral Kafka credentials at $directory"
