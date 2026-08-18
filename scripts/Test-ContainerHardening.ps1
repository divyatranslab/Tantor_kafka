[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

$dockerfiles = Get-ChildItem -LiteralPath $repositoryRoot -Filter Dockerfile -Recurse -File
Assert-True ($dockerfiles.Count -gt 0) 'No Dockerfiles were found.'
foreach ($dockerfile in $dockerfiles) {
    $fromLines = Get-Content -LiteralPath $dockerfile.FullName |
        Where-Object { $_ -match '^FROM\s+' }
    foreach ($line in $fromLines) {
        $isPinned = $line -match '^FROM\s+[^\s:]+(?::[^\s@]+)?@sha256:[0-9a-f]{64}(?:\s+AS\s+\S+)?$'
        Assert-True $isPinned "Dockerfile base image is not tag-and-digest pinned: $($dockerfile.FullName): $line"
    }
}

$composeFiles = @(
    Join-Path $repositoryRoot 'podman-compose.yml'
    Join-Path $repositoryRoot 'podman-compose.production.yml'
    Join-Path $repositoryRoot 'docker-compose-test.yml'
)
foreach ($composeFile in $composeFiles) {
    foreach ($line in Get-Content -LiteralPath $composeFile) {
        if ($line -notmatch '^\s*image:\s*(.+?)\s*$') {
            continue
        }
        $reference = $Matches[1]
        $isReleaseVariable = $reference -match '^\$\{[A-Z0-9_]+:\?.+\}$'
        $isPinned = $reference -match '@sha256:[0-9a-f]{64}$'
        Assert-True ($isReleaseVariable -or $isPinned) "Compose image is not immutable: ${composeFile}: $reference"
    }
}

$productionCompose = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'podman-compose.production.yml')
foreach ($control in @(
    'read_only: true',
    'cap_drop:',
    'no-new-privileges:true',
    'pids_limit:',
    'healthcheck:',
    'internal: true',
    'POSTGRES_PASSWORD_FILE',
    'SPRING_CONFIG_IMPORT: optional:configtree:/run/secrets/'
)) {
    Assert-True ($productionCompose.Contains($control)) "Production Compose control is missing: $control"
}
$publishedPortBlocks = [regex]::Matches($productionCompose, '(?m)^\s{4}ports:\s*$').Count
Assert-True ($publishedPortBlocks -eq 1) 'Only the UI may publish production host ports.'

$nginxProduction = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'tantor-ui\nginx.production.conf')
foreach ($header in @(
    'Strict-Transport-Security',
    'Content-Security-Policy',
    'X-Frame-Options',
    'X-Content-Type-Options',
    'Referrer-Policy',
    'Permissions-Policy'
)) {
    Assert-True ($nginxProduction.Contains($header)) "Nginx security header is missing: $header"
}
Assert-True ($nginxProduction.Contains('ssl_protocols TLSv1.2 TLSv1.3;')) 'Nginx TLS protocol policy is missing.'

$kubernetesTemplate = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'tantor-artifact-repository\deploy\k8s\artifact-repository.yaml.template')
foreach ($control in @(
    'automountServiceAccountToken: false',
    'allowPrivilegeEscalation: false',
    'readOnlyRootFilesystem: true',
    'type: RuntimeDefault',
    '- ALL'
)) {
    Assert-True ($kubernetesTemplate.Contains($control)) "Kubernetes security control is missing: $control"
}

$releaseScript = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'package-deployment.ps1')
Assert-True ($releaseScript.Contains('trivy image')) 'Release packaging does not scan images with Trivy.'
Assert-True ($releaseScript.Contains('syft scan')) 'Release packaging does not generate image SBOMs.'
Assert-True ($releaseScript.Contains('manifest.lock.json')) 'Release packaging does not create an image manifest.'
Assert-True (-not ($releaseScript -match '(?i)(?:^|:)latest(?:@|\s|''|")')) 'Release packaging contains a latest image reference.'

Write-Host 'Container hardening assertions passed.'
