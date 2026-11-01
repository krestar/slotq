param(
    [Parameter(Mandatory)][string]$Result,
    [Parameter(Mandatory)][string]$ResultDigest,
    [Parameter(Mandatory)][string]$Plan,
    [Parameter(Mandatory)][string]$PlanDigest,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path $PSScriptRoot -Parent
$safeRoot = [IO.Path]::GetFullPath((Join-Path $repo 'build')) + [IO.Path]::DirectorySeparatorChar
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $destination.StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $destination)) {
    throw 'Use a new ignored root build directory; original evidence is never edited.'
}
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
if ((Hash $Result) -cne $ResultDigest -or (Hash $Plan) -cne $PlanDigest) { throw 'Original evidence digest mismatch.' }
New-Item -ItemType Directory -Path $destination | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($identity)
$acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
Set-Acl -LiteralPath $destination -AclObject $acl
# Copies include a synthetic dump and inherit the protected evidence directory ACL.
$original = Split-Path $Result -Parent
Copy-Item -LiteralPath $original -Destination (Join-Path $destination 'backup') -Recurse
foreach ($name in @('manifest.json','raw.json','recovery.json')) {
    Copy-Item -LiteralPath (Join-Path (Split-Path $original -Parent) $name) -Destination $destination
}
$copy = Join-Path $destination 'backup/result.json'
$baseline = Get-Content -LiteralPath $Result -Raw
$cases = [Collections.Generic.List[object]]::new()
function Review([string]$Name, [string]$Expected, [string]$Digest) {
    $review = Join-Path $destination "$Name.json"
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $PSScriptRoot 'verify-backup-recovery.ps1') `
        -Result $copy -ResultDigest $Digest -Plan $Plan -PlanDigest $PlanDigest -Output $review | Out-Null
    $status = (Get-Content -LiteralPath $review -Raw | ConvertFrom-Json).status
    if ($status -cne $Expected -or (($LASTEXITCODE -eq 0) -ne ($Expected -ceq 'PASS'))) { throw "$Name expected $Expected, got $status (exit $LASTEXITCODE)" }
    $cases.Add(@{ case=$Name; expected=$Expected; actual=$status })
}
Review 'untouched-copy' 'PASS' $ResultDigest
$mutations = [ordered]@{
    'missing-security-gate' = { param($r) $r.checks.PSObject.Properties.Remove('securityAuthority') }
    'failed-revocation' = { param($r) $r.checks.securityReconciled='FAIL' }
    'rpo-exceeded' = { param($r) $r.rpoMillis=[long]::MaxValue }
    'rto-exceeded' = { param($r) $r.rtoMillis=[long]::MaxValue }
    'missing-clock' = { param($r) $r.rtoMillis=$null }
    'pilot-is-not-qualification' = { param($r) $r.kind='PILOT_EXECUTION' }
    'incomplete-execution' = { param($r) $r.complete=$false }
    'missing-original-history' = { param($r) $r.artifactDigests.PSObject.Properties.Remove('preserved-history.json') }
}
foreach ($case in $mutations.GetEnumerator()) {
    $record = $baseline | ConvertFrom-Json
    & $case.Value $record
    $record | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $copy -Encoding utf8NoBOM
    $expected = if ($case.Key -in @('missing-security-gate','missing-clock','missing-original-history')) { 'INCOMPLETE' } else { 'FAIL' }
    # Deliberately re-pin the edited record: structural gates must still reject it.
    Review $case.Key $expected (Hash $copy)
}
Copy-Item -LiteralPath $Result -Destination $copy
Add-Content -LiteralPath (Join-Path $destination 'backup/database.sql') -Value 'corrupt synthetic copy only'
Review 'corrupt-dump' 'FAIL' $ResultDigest
$cases | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $destination 'regression-summary.json') -Encoding utf8NoBOM
Write-Output "PASS ($($cases.Count) evidence-review regression cases; no new recovery execution)"
