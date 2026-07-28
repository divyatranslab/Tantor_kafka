[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$nginxRoot = Join-Path $PSScriptRoot ".runtime\nginx"
$nginxExe = Join-Path $nginxRoot "nginx.exe"

if (!(Test-Path $nginxExe)) {
    Write-Host "Local Nginx is not installed." -ForegroundColor Yellow
    exit 0
}

Push-Location $nginxRoot
try {
    & $nginxExe -p "$nginxRoot\" -c "conf\tantor-local.conf" -s quit
} finally {
    Pop-Location
}

Write-Host "Tantor local security proxy stopped." -ForegroundColor Green
