$ErrorActionPreference = 'Stop'
$localPath = Join-Path $PSScriptRoot '.local'
if (Test-Path -LiteralPath $localPath) { throw 'Existing .local credentials must be preserved; use the existing files.' }
New-Item -ItemType Directory -Path $localPath | Out-Null
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$metricsPassword = New-Secret
$tracePassword = New-Secret
$grafanaPassword = New-Secret
$scrapeToken = New-Secret
# Password travels over stdin, not command arguments or shell history.
$metricsHash = $metricsPassword | docker run --rm -i httpd:2.4-alpine htpasswd -niB telemetry
if ($LASTEXITCODE -ne 0) { throw 'Could not generate Prometheus password hash' }
$traceHash = $tracePassword | docker run --rm -i httpd:2.4-alpine htpasswd -niB telemetry
if ($LASTEXITCODE -ne 0) { throw 'Could not generate OTLP password hash' }
$bcrypt = ($metricsHash | Where-Object { $_ -like 'telemetry:*' }) -replace '^telemetry:', ''
Set-Content -LiteralPath (Join-Path $localPath 'prometheus-web.yml') -Value "basic_auth_users:`n  telemetry: '$bcrypt'" -Encoding utf8NoBOM
Set-Content -LiteralPath (Join-Path $localPath 'trace.htpasswd') -Value ($traceHash | Where-Object { $_ -like 'telemetry:*' }) -Encoding utf8NoBOM
Set-Content -LiteralPath (Join-Path $localPath 'scrape-token') -Value $scrapeToken -NoNewline -Encoding utf8NoBOM
Set-Content -LiteralPath (Join-Path $localPath 'compose.env') -Value "METRICS_PASSWORD=$metricsPassword`nGRAFANA_PASSWORD=$grafanaPassword" -Encoding utf8NoBOM
$authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("telemetry:$tracePassword"))
Set-Content -LiteralPath (Join-Path $localPath 'product.env') -Value "SLOTQ_OBSERVABILITY_SCRAPE_TOKEN=$scrapeToken`nSLOTQ_TELEMETRY_OTLP_ENDPOINT=http://127.0.0.1:4318/v1/traces`nSLOTQ_TELEMETRY_OTLP_AUTHORIZATION=$authorization" -Encoding utf8NoBOM
Write-Output 'Credentials saved under ignored .local. Restrict directory permissions to the current operator before use.'
