[CmdletBinding()]
param(
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$nginxRoot = Join-Path $root ".runtime\nginx"
$nginxExe = Join-Path $nginxRoot "nginx.exe"
$template = Join-Path $root "tantor-ui\nginx.local.conf.template"
$config = Join-Path $nginxRoot "conf\tantor-local.conf"
$certRoot = Join-Path $root "scratch\certs"

if (!(Test-Path $nginxExe)) {
    throw "Local Nginx was not found at $nginxExe."
}
if (!(Test-Path $template)) {
    throw "Nginx template was not found at $template."
}

foreach ($required in @("server.crt", "server.key", "ca.crt")) {
    if (!(Test-Path (Join-Path $certRoot $required))) {
        throw "Missing local TLS file: $(Join-Path $certRoot $required)"
    }
}

foreach ($line in Get-Content (Join-Path $root ".env")) {
    $trimmed = $line.Trim()
    if ($trimmed -and !$trimmed.StartsWith("#") -and $trimmed.Contains("=")) {
        $name, $value = $trimmed.Split("=", 2)
        if ($name.Trim() -eq "TANTOR_PROXY_SECRET") {
            $proxySecret = $value.Trim().Trim('"').Trim("'")
        }
    }
}
if ([string]::IsNullOrWhiteSpace($proxySecret)) {
    throw "TANTOR_PROXY_SECRET is missing from .env."
}

function Convert-NginxPath([string]$path) {
    return ([System.IO.Path]::GetFullPath($path) -replace "\\", "/")
}

$rendered = Get-Content $template -Raw
$rendered = $rendered.Replace("__SERVER_CERT__", (Convert-NginxPath (Join-Path $certRoot "server.crt")))
$rendered = $rendered.Replace("__SERVER_KEY__", (Convert-NginxPath (Join-Path $certRoot "server.key")))
$rendered = $rendered.Replace("__AGENT_CA_CERT__", (Convert-NginxPath (Join-Path $certRoot "ca.crt")))
$rendered = $rendered.Replace("__PROXY_SECRET__", $proxySecret)
[System.IO.File]::WriteAllText($config, $rendered)

Push-Location $nginxRoot
try {
    & $nginxExe -t -p "$nginxRoot\" -c "conf\tantor-local.conf"
    if ($LASTEXITCODE -ne 0) {
        throw "Nginx configuration validation failed."
    }

    $running = @(Get-CimInstance Win32_Process -Filter "Name = 'nginx.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($nginxRoot) })

    if ($Restart -and $running.Count -gt 0) {
        & $nginxExe -p "$nginxRoot\" -c "conf\tantor-local.conf" -s quit
        Start-Sleep -Seconds 1
        $running = @()
    }

    if ($running.Count -eq 0) {
        Start-Process -FilePath $nginxExe `
            -ArgumentList @("-p", "$nginxRoot\", "-c", "conf\tantor-local.conf") `
            -WorkingDirectory $nginxRoot `
            -WindowStyle Hidden
    } else {
        & $nginxExe -p "$nginxRoot\" -c "conf\tantor-local.conf" -s reload
    }
} finally {
    Pop-Location
}

Write-Host "Tantor local security proxy is available at https://localhost:9443" -ForegroundColor Green
