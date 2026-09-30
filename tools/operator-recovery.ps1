[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$BaseUri,
    [Parameter(Mandatory)][Guid]$Tenant,
    [Parameter(Mandatory)][ValidateSet('deliveries','target','publication','replay','publication-recover','operation','audit')][string]$Action,
    [string]$Consumer,
    [Guid]$Event,
    [Guid]$Registration,
    [Guid]$Operation,
    [string]$Destination,
    [string]$RequestFile,
    [int]$Limit = 50,
    [Guid]$AfterEvent,
    [Guid]$AfterRegistration,
    [Guid]$After
)
$ErrorActionPreference = 'Stop'
if ($BaseUri.Scheme -ne 'https' -or $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) {
    throw 'BaseUri must be a private HTTPS origin without credentials, query or fragment.'
}
if (-not $env:SLOTQ_OPERATOR_TOKEN -or $env:SLOTQ_OPERATOR_TOKEN -notmatch '^sqop_[0-9a-f]{64}$') {
    throw 'Load your personal credential from an external secret store into SLOTQ_OPERATOR_TOKEN.'
}
if ($Limit -lt 1 -or $Limit -gt 100) { throw 'Limit must be 1..100.' }
if ($Action -in @('deliveries','target','replay','operation','audit') -and $Consumer -notmatch '^[A-Za-z0-9._-]{1,100}$') {
    throw 'An exact logical Consumer is required.'
}
$zero = [Guid]::Empty
$root = $BaseUri.GetLeftPart([System.UriPartial]::Authority) + '/internal/operations/tenants/' + $Tenant
$method = 'GET'
$body = $null
switch ($Action) {
    'deliveries' {
        $path = "/consumers/$Consumer/deliveries?limit=$Limit"
        if ($AfterEvent -ne $zero -or $AfterRegistration -ne $zero) {
            if ($AfterEvent -eq $zero -or $AfterRegistration -eq $zero) { throw 'Both pagination UUIDs are required.' }
            $path += "&afterEvent=$AfterEvent&afterRegistration=$AfterRegistration"
        }
    }
    'target' { $path = "/consumers/$Consumer/deliveries/$Event/$Registration" }
    'publication' {
        if ($Destination -notmatch '^[A-Za-z0-9._-]{1,100}$') { throw 'Exact destination is required.' }
        $path = "/publications/$Event`?destination=$Destination"
    }
    'replay' { $method = 'POST'; $path = "/consumers/$Consumer/deliveries/$Event/$Registration/replay" }
    'publication-recover' { $method = 'POST'; $path = "/publications/$Event/recover" }
    'operation' { $path = "/consumers/$Consumer/operations/$Operation" }
    'audit' {
        $path = "/consumers/$Consumer/audit?limit=$Limit"
        if ($After -ne $zero) { $path += "&after=$After" }
    }
}
if ($Action -in @('target','publication','replay','publication-recover') -and $Event -eq $zero) { throw 'Exact Event is required.' }
if ($Action -in @('target','replay') -and $Registration -eq $zero) { throw 'Original Registration is required.' }
if ($Action -eq 'operation' -and $Operation -eq $zero) { throw 'Exact Operation is required.' }
if ($method -eq 'POST') {
    if (-not $RequestFile) { throw 'Persist an exact request JSON with an operationId before submission.' }
    $body = Get-Content -LiteralPath $RequestFile -Raw
    $parsed = $body | ConvertFrom-Json
    if (-not $parsed.operationId -or [Guid]$parsed.operationId -eq $zero) { throw 'The request must contain a durable operationId.' }
}
$arguments = @{
    Uri = $root + $path
    Method = $method
    Headers = @{ Authorization = 'Bearer ' + $env:SLOTQ_OPERATOR_TOKEN }
    TimeoutSec = 20
    MaximumRedirection = 0
}
if ($body) { $arguments.ContentType = 'application/json'; $arguments.Body = [Text.Encoding]::UTF8.GetBytes($body) }
try { Invoke-RestMethod @arguments | ConvertTo-Json -Depth 10 }
catch {
    # Never display request headers or the credential, and never auto-retry a mutation.
    Write-Error 'Operations request failed or its outcome is unknown. Query the same durable operation, then retry only the saved exact request. Do not generate a new operation ID.'
    exit 1
}
