# Create / extend local-only secrets. Never print values or overwrite existing passwords.
$ErrorActionPreference = "Stop"
$target = Join-Path $PSScriptRoot ".env.apm"
function New-LabSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}
if (-not (Test-Path -LiteralPath $target)) {
    $lines = @(
        "LAB_DB_PASSWORD=$(New-LabSecret)",
        "LAB_DB_ROOT_PASSWORD=$(New-LabSecret)",
        "LAB_JWT_SECRET=$(New-LabSecret)"
    )
    [System.IO.File]::WriteAllLines($target, $lines, [System.Text.Encoding]::ASCII)
    Write-Host "Created local-only lab credentials."
}
# Stage 2: retain existing Stage 1 secrets and append the Grafana password only.
if (-not (Select-String -LiteralPath $target -Pattern '^LAB_GRAFANA_PASSWORD=' -Quiet)) {
    [System.IO.File]::AppendAllText($target, "LAB_GRAFANA_PASSWORD=$(New-LabSecret)`r`n", [System.Text.Encoding]::ASCII)
    Write-Host "Added a new local-only Grafana admin password."
} else {
    Write-Host "Existing Grafana admin password preserved."
}
Write-Host "Credentials are stored only in the git-ignored perf/.env.apm."
