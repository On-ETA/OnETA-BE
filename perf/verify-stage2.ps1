# Verify Grafana and Prometheus without disclosing the local Grafana password.
$ErrorActionPreference = "Stop"
$envFile = Join-Path $PSScriptRoot ".env.apm"
if (-not (Test-Path -LiteralPath $envFile)) { throw "Run perf/prepare.ps1 first" }
$line = @(Get-Content -LiteralPath $envFile | Where-Object { $_.StartsWith("LAB_GRAFANA_PASSWORD=") })
if ($line.Count -ne 1) { throw "Grafana password missing from local lab env" }
$password = $line[0].Substring("LAB_GRAFANA_PASSWORD=".Length)
$encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("admin:$password"))
$headers = @{ Authorization = "Basic $encoded" }
$health = Invoke-RestMethod -Uri "http://127.0.0.1:13000/api/health"
if ($health.database -ne "ok") { throw "Grafana health is not OK" }
$dataSource = Invoke-RestMethod -Uri "http://127.0.0.1:13000/api/datasources/uid/oneta-prometheus" -Headers $headers
if ($dataSource.type -ne "prometheus") { throw "Prometheus data source not found" }
$dashboards = @(Invoke-RestMethod -Uri "http://127.0.0.1:13000/api/search?query=OnETA" -Headers $headers)
if (-not ($dashboards | Where-Object { $_.uid -eq "oneta-apm-local" })) {
    throw "Provisioned OnETA dashboard not found"
}
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$args = @("compose", "--env-file", (Join-Path $PSScriptRoot ".env.apm"), "-f",
          (Join-Path $PSScriptRoot "compose.yaml"), "exec", "-T",
          "prometheus", "wget", "-qO-", "http://localhost:9090/api/v1/targets")
$json = & $docker @args
if ($LASTEXITCODE -ne 0) { throw "Could not inspect Prometheus targets" }
$targets = $json | ConvertFrom-Json
$oneta = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq "oneta-apm" })
if ($oneta.Count -ne 1 -or $oneta[0].health -ne "up") {
    throw "OnETA Prometheus target is not UP"
}
Write-Host "Grafana healthy; Prometheus data source and OnETA dashboard provisioned."
Write-Host "OnETA Prometheus scrape target: UP."
Write-Host "Open: http://127.0.0.1:13000/d/oneta-apm-local"
