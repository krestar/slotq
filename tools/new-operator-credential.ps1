[CmdletBinding()]
param([Parameter(Mandatory)][string]$SecretPath)
$ErrorActionPreference = 'Stop'
$path = [IO.Path]::GetFullPath($SecretPath)
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')) + [IO.Path]::DirectorySeparatorChar
if ($path.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $path)) {
    throw 'Choose a new private file outside the repository, on a protected volume.'
}
$random = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($random)
$token = 'sqop_' + [Convert]::ToHexString($random).ToLowerInvariant()
$credential = [Guid]::NewGuid()
# Create with an ACL restricted to the current Windows user before writing the secret.
$security = [Security.AccessControl.FileSecurity]::new()
$security.SetAccessRuleProtection($true,$false)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','Allow'))
$file = [IO.FileInfo]::new($path)
$stream = [IO.FileSystemAclExtensions]::Create($file,[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::Write, [IO.FileShare]::None,4096,[IO.FileOptions]::None,$security)
try {
    $encoded = [Text.Encoding]::UTF8.GetBytes($token)
    $stream.Write($encoded,0,$encoded.Length)
} finally { $stream.Dispose() }
$digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($token)))
# Public provisioning metadata only. Transfer the private file into the operator's secret store.
[pscustomobject]@{ CredentialId=$credential; TokenHashHex=$digest; SecretPath=$path } | ConvertTo-Json
$token = $null
