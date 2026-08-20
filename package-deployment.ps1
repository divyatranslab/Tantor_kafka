[CmdletBinding()]
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version = '1.0.0',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'output')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$postgresReference = 'docker.io/library/postgres:13.23@sha256:4689940c683801b4ab839ab3b0a0a3555a5fe425371422310944e89eca7d8068'
$bundleDirectory = Join-Path $OutputDirectory "tantor-$Version"
$archivePath = Join-Path $OutputDirectory "tantor-$Version.tar.gz"
$imagesDirectory = Join-Path $bundleDirectory 'images'
$scanDirectory = Join-Path $bundleDirectory 'security-scans'
$sbomDirectory = Join-Path $bundleDirectory 'sbom'

if (-not (Get-Command podman -ErrorAction SilentlyContinue)) {
    throw 'Podman is required to build the deployment bundle.'
}
if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
    throw 'Trivy is required to scan every release image.'
}
if (-not (Get-Command syft -ErrorAction SilentlyContinue)) {
    throw 'Syft is required to generate an SBOM for every release image.'
}
if (Test-Path -LiteralPath $bundleDirectory) {
    throw "Refusing to overwrite existing bundle directory: $bundleDirectory"
}
if (Test-Path -LiteralPath $archivePath) {
    throw "Refusing to overwrite existing bundle archive: $archivePath"
}

New-Item -ItemType Directory -Force -Path $imagesDirectory, $scanDirectory, $sbomDirectory | Out-Null

function Invoke-Podman {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & podman @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Podman command failed: podman $($Arguments -join ' ')"
    }
}

function Get-ImmutableImageReference {
    param([Parameter(Mandatory = $true)][string]$Image)
    $digest = (& podman image inspect --format '{{.Digest}}' $Image).Trim()
    if ($LASTEXITCODE -ne 0 -or $digest -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "Image does not expose an immutable manifest digest: $Image"
    }
    return "$Image@$digest"
}

$applicationImages = [ordered]@{
    'tantor-ui' = "localhost/tantor-ui:$Version"
    'tantor-server' = "localhost/tantor-server:$Version"
    'tantor-artifact-repository' = "localhost/tantor-artifact-repository:$Version"
}

Write-Host "Running release quality gates..."
Write-Host "Validating UI..."
Invoke-Podman run --rm -v "$($PSScriptRoot):/app:Z" -w /app/tantor-ui docker.io/library/node:20-bookworm bash -c "npm ci && npm run lint:ci"

Write-Host "Validating Server..."
Invoke-Podman run --rm -v "$($PSScriptRoot):/app:Z" -w /app/tantor-server docker.io/library/maven:3.9.16-eclipse-temurin-21-noble mvn -B verify

Write-Host "Validating Artifact Repository..."
Invoke-Podman run --rm -v "$($PSScriptRoot):/app:Z" -w /app/tantor-artifact-repository docker.io/library/maven:3.9.16-eclipse-temurin-21-noble mvn -B verify

Write-Host "Validating Agents..."
Invoke-Podman run --rm -v "$($PSScriptRoot):/app:Z" -w /app/tantor-agent docker.io/library/golang:1.22-bookworm go test ./...
Invoke-Podman run --rm -v "$($PSScriptRoot):/app:Z" -w /app/tantor-discovery-agent docker.io/library/golang:1.22-bookworm go test ./...


Write-Host "Building Tantor $Version images from digest-pinned Dockerfiles..."
Invoke-Podman build --pull=always --build-arg NGINX_CONFIG=nginx.production.conf --label "org.opencontainers.image.version=$Version" --tag $applicationImages['tantor-ui'] (Join-Path $PSScriptRoot 'tantor-ui')
Invoke-Podman build --pull=always --label "org.opencontainers.image.version=$Version" --tag $applicationImages['tantor-server'] (Join-Path $PSScriptRoot 'tantor-server')
Invoke-Podman build --pull=always --label "org.opencontainers.image.version=$Version" --tag $applicationImages['tantor-artifact-repository'] (Join-Path $PSScriptRoot 'tantor-artifact-repository')
Invoke-Podman pull $postgresReference

$lockedImages = [ordered]@{}
foreach ($entry in $applicationImages.GetEnumerator()) {
    $lockedImages[$entry.Key] = Get-ImmutableImageReference -Image $entry.Value
}
$lockedImages['postgres'] = $postgresReference

foreach ($entry in $lockedImages.GetEnumerator()) {
    if ($entry.Value -match '(^|:)latest(@|$)' -or $entry.Value -notmatch '@sha256:[0-9a-f]{64}$') {
        throw "Release image is not immutable: $($entry.Value)"
    }
    $sourceImage = if ($entry.Key -eq 'postgres') { $postgresReference } else { $applicationImages[$entry.Key] }
    if ($entry.Key -ne 'postgres') {
        $runtimeUser = (& podman image inspect --format '{{.Config.User}}' $sourceImage).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($runtimeUser) -or $runtimeUser -eq '0' -or $runtimeUser -eq 'root') {
            throw "Application image does not declare a non-root runtime user: $($entry.Value)"
        }
    }

    $archive = Join-Path $imagesDirectory "$($entry.Key).oci.tar"
    Invoke-Podman save --format oci-archive --output $archive $sourceImage

    $scanReport = Join-Path $scanDirectory "$($entry.Key).trivy.json"
    & trivy image --quiet --scanners vuln --format json --output $scanReport --input $archive
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $scanReport)) {
        throw "Image vulnerability scan failed: $($entry.Value)"
    }

    $sbomPath = Join-Path $sbomDirectory "$($entry.Key).spdx.json"
    & syft scan "oci-archive:$archive" --output "spdx-json=$sbomPath"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $sbomPath)) {
        throw "Image SBOM generation failed: $($entry.Value)"
    }
}

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'podman-compose.production.yml') -Destination (Join-Path $bundleDirectory 'compose.yml')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'deploy\production\README.md') -Destination (Join-Path $bundleDirectory 'README.md')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'deploy\production\secrets\README.md') -Destination (Join-Path $bundleDirectory 'SECRETS.md')

$productionEnvironment = @(
    "TANTOR_UI_IMAGE=$($lockedImages['tantor-ui'])"
    "TANTOR_SERVER_IMAGE=$($lockedImages['tantor-server'])"
    "TANTOR_ARTIFACT_REPOSITORY_IMAGE=$($lockedImages['tantor-artifact-repository'])"
    "POSTGRES_IMAGE=$($lockedImages['postgres'])"
    'TANTOR_SECRETS_DIR=./secrets'
    'TANTOR_MONITORING_MODE=direct'
    'TANTOR_PROMETHEUS_URL=http://prometheus:9090'
)
Set-Content -LiteralPath (Join-Path $bundleDirectory '.env.production') -Value $productionEnvironment -Encoding utf8NoBOM

$startScript = @'
#!/usr/bin/env bash
set -euo pipefail

sha256sum --check SHA256SUMS
test "$(stat -c '%a' ./secrets)" = "700" || {
  echo "The ./secrets directory must have mode 0700" >&2
  exit 1
}

required_secrets=(
  TANTOR_DB_USER
  TANTOR_DB_PASSWORD
  TANTOR_ENCRYPTION_KEY
  TANTOR_JWT_SECRET
  tls.crt
  tls.key
)
for secret in "${required_secrets[@]}"; do
  test -s "./secrets/${secret}" || {
    echo "Missing required secret: ./secrets/${secret}" >&2
    exit 1
  }
  test "$(stat -c '%a' "./secrets/${secret}")" = "444" || {
    echo "Secret must have mode 0444: ./secrets/${secret}" >&2
    exit 1
  }
done

while IFS= read -r archive; do
  podman load --input "$archive"
done < <(find ./images -maxdepth 1 -type f -name '*.oci.tar' -print | sort)

podman-compose --env-file .env.production --file compose.yml config >/dev/null
podman-compose --env-file .env.production --file compose.yml up --detach
podman-compose --env-file .env.production --file compose.yml ps
'@
Set-Content -LiteralPath (Join-Path $bundleDirectory 'start.sh') -Value $startScript -Encoding utf8NoBOM

$sourceCommit = (& git -C $PSScriptRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to determine the source commit for the release manifest.'
}
$manifest = [ordered]@{
    product = 'Tantor'
    version = $Version
    sourceCommit = $sourceCommit
    createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    target = 'linux/amd64'
    images = @($lockedImages.GetEnumerator() | ForEach-Object {
        [ordered]@{ name = $_.Key; reference = $_.Value }
    })
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $bundleDirectory 'manifest.lock.json') -Encoding utf8NoBOM

$checksummedFiles = Get-ChildItem -LiteralPath $bundleDirectory -File -Recurse |
    Where-Object Name -ne 'SHA256SUMS' |
    Sort-Object FullName
$checksumLines = foreach ($file in $checksummedFiles) {
    $relative = [IO.Path]::GetRelativePath($bundleDirectory, $file.FullName).Replace('\', '/')
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    "$hash  $relative"
}
Set-Content -LiteralPath (Join-Path $bundleDirectory 'SHA256SUMS') -Value $checksumLines -Encoding ascii

tar -czf $archivePath -C $OutputDirectory (Split-Path -Leaf $bundleDirectory)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to create the deployment archive.'
}

Write-Host "Created immutable deployment bundle: $archivePath"
