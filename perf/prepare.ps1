# Generate local-only secrets; never overwrite an existing lab environment.
$ErrorActionPreference = "Stop"
$target = Join-Path $PSScriptRoot ".env.apm"
if (Test-Path $target) {
    Write-Host "Local lab environment already exists. No changes made."
    exit 0
}
function New-LabSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
$dbSecret = New-LabSecret
$rootSecret = New-LabSecret
$jwtSecret = New-LabSecret
$lines = @(
    "LAB_DB_PASSWORD=$dbSecret",
    "LAB_DB_ROOT_PASSWORD=$rootSecret",
    "LAB_JWT_SECRET=$jwtSecret"
)
[System.IO.File]::WriteAllLines($target, $lines, [System.Text.Encoding]::ASCII)
Write-Host "Created perf/.env.apm with random local-only secrets (git-ignored)."
