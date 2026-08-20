[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$postgres16Reference = 'docker.io/library/postgres:16.14@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b'

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

$developmentCompose = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'podman-compose.yml')
Assert-True ($developmentCompose.Contains($postgres16Reference)) 'Development Compose does not use the approved PostgreSQL 16.14 image.'
Assert-True ($developmentCompose.Contains('127.0.0.1:5432:5432')) 'Development PostgreSQL must bind to localhost only.'
Assert-True ($developmentCompose.Contains('jdbc:postgresql://database:5432/tantor')) 'Artifact repository database URL is not explicit in development Compose.'
Assert-True ($developmentCompose.Contains('condition: service_healthy')) 'Development Compose does not gate startup on service health.'
Assert-True ($developmentCompose.Contains('pg_isready')) 'Development Compose lacks a PostgreSQL readiness check.'

$testCompose = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'docker-compose-test.yml')
Assert-True ($testCompose.Contains($postgres16Reference)) 'Test Compose does not use the approved PostgreSQL 16.14 image.'
Assert-True ($testCompose.Contains('127.0.0.1:5433:5432')) 'Test PostgreSQL must bind to localhost only.'

$artifactConfiguration = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'tantor-artifact-repository\src\main\resources\application.yml')
Assert-True ($artifactConfiguration.Contains('url: ${TANTOR_DB_URL}')) 'Artifact repository database URL is not required.'
Assert-True (-not ($artifactConfiguration.Contains('jdbc:postgresql://localhost'))) 'Artifact repository contains a localhost database fallback.'
Assert-True (-not ($artifactConfiguration.Contains('${TANTOR_DB_USER:'))) 'Artifact repository contains a database username fallback.'
Assert-True (-not ($artifactConfiguration.Contains('${TANTOR_DB_PASSWORD:'))) 'Artifact repository contains a database password fallback.'
Assert-True ($artifactConfiguration.Contains('include: readinessState,db,artifactSchema')) 'Artifact readiness does not include database and schema health indicators.'
Assert-True ($artifactConfiguration.Contains('initialization-fail-timeout:')) 'Artifact database startup retry is not bounded.'

$schemaIndicatorPath = Join-Path $repositoryRoot 'tantor-artifact-repository\src\main\java\io\translab\tantor\artifact\config\ArtifactSchemaHealthIndicator.java'
$schemaIndicator = Get-Content -Raw -LiteralPath $schemaIndicatorPath
foreach ($control in @(
    'to_regclass(''public.kf_artifact'')',
    'FROM flyway_schema_history',
    'success = TRUE',
    '@Component("artifactSchema")'
)) {
    Assert-True ($schemaIndicator.Contains($control)) "Artifact schema readiness control is missing: $control"
}
Assert-True (-not ($schemaIndicator -match '(?i)\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE)\b')) 'Artifact schema health indicator is not read-only.'

$artifactBlock = [regex]::Match(
    $productionCompose,
    '(?ms)^  tantor-artifact-repository:.*?(?=^  tantor-server:)'
).Value
Assert-True (-not [string]::IsNullOrWhiteSpace($artifactBlock)) 'Production Artifact Repository service block was not found.'
Assert-True ($artifactBlock.Contains('tantor-server:')) 'Artifact repository is not gated on the schema-migrating server.'
Assert-True ($artifactBlock.Contains('condition: service_healthy')) 'Artifact repository schema dependency is not health-gated.'

$serverConfiguration = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot 'tantor-server\src\main\resources\application.yml')
foreach ($flywayControl in @(
    'baseline-on-migrate: false',
    'clean-disabled: true',
    'ignore-missing-migrations: false',
    'validate-on-migrate: true'
)) {
    Assert-True ($serverConfiguration.Contains($flywayControl)) "Strict Flyway control is missing: $flywayControl"
}

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
Assert-True ($releaseScript.Contains($postgres16Reference)) 'Release packaging does not use the approved PostgreSQL 16.14 image.'
foreach ($control in @(
    'compose up --detach --no-deps "$service"',
    'start_and_wait database',
    'start_and_wait tantor-server',
    'start_and_wait tantor-artifact-repository',
    'start_and_wait tantor-ui'
)) {
    Assert-True ($releaseScript.Contains($control)) "Production startup sequencing control is missing: $control"
}
Assert-True ($releaseScript.Contains('trivy image')) 'Release packaging does not scan images with Trivy.'
Assert-True ($releaseScript.Contains('syft scan')) 'Release packaging does not generate image SBOMs.'
Assert-True ($releaseScript.Contains('manifest.lock.json')) 'Release packaging does not create an image manifest.'
Assert-True (-not ($releaseScript -match '(?i)(?:^|:)latest(?:@|\s|''|")')) 'Release packaging contains a latest image reference.'

Write-Host 'Container hardening assertions passed.'
