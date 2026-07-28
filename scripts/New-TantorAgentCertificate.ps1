[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]{1,128}$')]
    [string]$HostId,

    [string]$CertificateRoot = (Join-Path $PSScriptRoot "..\scratch\certs"),

    [ValidateRange(1, 825)]
    [int]$ValidDays = 365
)

$ErrorActionPreference = "Stop"

function Convert-ToWslPath {
    param([Parameter(Mandatory = $true)][string]$WindowsPath)

    $resolved = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($resolved -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only absolute Windows drive paths can be converted for WSL: $resolved"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2] -replace "\\", "/"
    return "/mnt/$drive/$tail"
}

if (!(Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "WSL is required because this script uses its OpenSSL installation."
}

$root = [System.IO.Path]::GetFullPath($CertificateRoot)
$caCert = Join-Path $root "ca.crt"
$caKey = Join-Path $root "ca.key"
if (!(Test-Path $caCert) -or !(Test-Path $caKey)) {
    throw "Agent CA files were not found. Expected $caCert and $caKey."
}

$agentDir = Join-Path $root "agents\$HostId"
New-Item -ItemType Directory -Force -Path $agentDir | Out-Null

$key = Join-Path $agentDir "agent.key"
$csr = Join-Path $agentDir "agent.csr"
$cert = Join-Path $agentDir "agent.crt"
$chain = Join-Path $agentDir "agent-ca.crt"

if ((Test-Path $key) -or (Test-Path $cert)) {
    throw "Certificate material already exists for hostId $HostId at $agentDir."
}

$wslKey = Convert-ToWslPath $key
$wslCsr = Convert-ToWslPath $csr
$wslCert = Convert-ToWslPath $cert
$wslCaCert = Convert-ToWslPath $caCert
$wslCaKey = Convert-ToWslPath $caKey

& wsl.exe openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out $wslKey
if ($LASTEXITCODE -ne 0) { throw "Failed to generate the agent private key." }

& wsl.exe openssl req -new -sha256 `
    -key $wslKey `
    -out $wslCsr `
    -subj "/C=IN/O=Tantor Local Development/OU=Agents/CN=tantor-agent:$HostId" `
    -addext "subjectAltName=URI:spiffe://tantor.local/agent/$HostId" `
    -addext "extendedKeyUsage=clientAuth"
if ($LASTEXITCODE -ne 0) { throw "Failed to create the agent certificate request." }

& wsl.exe openssl x509 -req -sha256 `
    -in $wslCsr `
    -CA $wslCaCert `
    -CAkey $wslCaKey `
    -CAcreateserial `
    -days $ValidDays `
    -copy_extensions copy `
    -out $wslCert
if ($LASTEXITCODE -ne 0) { throw "Failed to sign the agent certificate." }

Copy-Item -LiteralPath $caCert -Destination $chain
Remove-Item -LiteralPath $csr

& wsl.exe openssl verify -CAfile $wslCaCert $wslCert
if ($LASTEXITCODE -ne 0) { throw "The generated agent certificate did not verify against the Agent CA." }

Write-Host "Generated unique certificate for hostId $HostId" -ForegroundColor Green
Write-Host "  Certificate: $cert"
Write-Host "  Private key: $key"
Write-Host "  Agent CA:    $chain"
Write-Host "  Certificate identity: CN=tantor-agent:$HostId"
