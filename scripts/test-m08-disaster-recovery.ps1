<#
.SYNOPSIS
Automated Disaster Recovery (DR) Drill for M-08.

.DESCRIPTION
Validates the complete backup and restore lifecycle.
Measures Recovery Time Objective (RTO).
Ensures representative data survives restoration.
#>

$ErrorActionPreference = "Stop"
$StartTime = Get-Date
$DbContainer = "dr-test-db"
$ServerContainer = "dr-test-server"
$RepoContainer = "dr-test-repo"
$Network = "dr-test-net"
$DbUser = "postgres"
$DbPassword = "dr-password"

Write-Host "=========================================="
Write-Host " M-08 DISASTER RECOVERY DRILL INITIATED   "
Write-Host "=========================================="

# Cleanup any previous runs
Write-Host "Cleaning up previous state..."
podman rm -f $ServerContainer $RepoContainer $DbContainer 2>$null
podman network rm $Network 2>$null

# 1. Create Isolated PostgreSQL
Write-Host "1. Creating isolated network and PostgreSQL container..."
podman network create $Network | Out-Null
podman run -d --name $DbContainer --network $Network -e POSTGRES_DB=tantor -e POSTGRES_USER=$DbUser -e POSTGRES_PASSWORD=$DbPassword docker.io/library/postgres:16.14@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b | Out-Null

Start-Sleep -Seconds 10

# Start server briefly to apply migrations and seed data
Write-Host "2. Starting Tantor Server to apply Flyway migrations and seed data..."
podman run -d --name $ServerContainer --network $Network -e SPRING_PROFILES_ACTIVE=dev -e TANTOR_DB_URL="jdbc:postgresql://$DbContainer:5432/tantor" -e TANTOR_DB_USER=$DbUser -e TANTOR_DB_PASSWORD=$DbPassword -e TANTOR_ENCRYPTION_KEY="12345678901234567890123456789012" -e TANTOR_JWT_SECRET="12345678901234567890123456789012" -e TANTOR_REPO_INTERNAL_URL="http://repo:8081" tantor-server:latest | Out-Null

Start-Sleep -Seconds 15

# Seed mock data
Write-Host "Seeding representative audit and cluster data..."
podman exec -i $DbContainer psql -U $DbUser -d tantor -c "INSERT INTO audit_logs (id, created_by, category, action, resource_type, status, created_time) VALUES (gen_random_uuid(), 'dr-test', 'SYSTEM', 'TEST', 'HOST', 'SUCCESS', now());"

# 3. Create Backup
Write-Host "3. Creating backup using backup-postgres.ps1..."
$env:TANTOR_DB_USER = $DbUser
.\scripts\backup-postgres.ps1 -ContainerName $DbContainer -BackupDir ".\dr-test-backups" -DatabaseId "tantor" -RetentionDays 1

$BackupFile = Get-ChildItem ".\dr-test-backups\*.dump" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $BackupFile) {
    throw "Backup file was not created!"
}

# 4. Destroy Database
Write-Host "4. Destroying the source database and server to simulate catastrophic failure..."
podman rm -f $ServerContainer $DbContainer | Out-Null

# 5. Restore Backup
$RestoreStartTime = Get-Date
Write-Host "5. Provisioning fresh database for restoration..."
podman run -d --name $DbContainer --network $Network -e POSTGRES_DB=tantor -e POSTGRES_USER=$DbUser -e POSTGRES_PASSWORD=$DbPassword docker.io/library/postgres:16.14@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b | Out-Null

Start-Sleep -Seconds 10

Write-Host "Restoring backup..."
.\scripts\restore-postgres.ps1 -BackupFile $BackupFile.FullName -ContainerName $DbContainer -DatabaseId "tantor" -Force

# 6. Validate schema
Write-Host "6. Validating schema and migrations..."
$TableCount = podman exec -i $DbContainer psql -U $DbUser -d tantor -t -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
if ([int]($TableCount.Trim()) -eq 0) { throw "Schema validation failed!" }

# 7. Start Server and Repo
Write-Host "7. Starting Tantor Server and Artifact Repository connected to restored DB..."
podman run -d --name $RepoContainer --network $Network -e SPRING_PROFILES_ACTIVE=dev -e TANTOR_DB_URL="jdbc:postgresql://$DbContainer:5432/tantor" -e TANTOR_DB_USER=$DbUser -e TANTOR_DB_PASSWORD=$DbPassword -e TANTOR_CORS_ALLOWED_ORIGINS="http://localhost" tantor-artifact-repository:latest | Out-Null

podman run -d --name $ServerContainer --network $Network -e SPRING_PROFILES_ACTIVE=dev -e TANTOR_DB_URL="jdbc:postgresql://$DbContainer:5432/tantor" -e TANTOR_DB_USER=$DbUser -e TANTOR_DB_PASSWORD=$DbPassword -e TANTOR_ENCRYPTION_KEY="12345678901234567890123456789012" -e TANTOR_JWT_SECRET="12345678901234567890123456789012" -e TANTOR_REPO_INTERNAL_URL="http://$RepoContainer:8081" tantor-server:latest | Out-Null

Start-Sleep -Seconds 20

# 8. Verify Artifact Repository readiness
Write-Host "8. Verifying Artifact Repository readiness..."
$RepoHealth = podman exec -i $RepoContainer curl -s http://localhost:8081/actuator/health/readiness | ConvertFrom-Json
if ($RepoHealth.status -ne "UP") { throw "Artifact repository is not ready!" }

# 9. Query representative records
Write-Host "9. Querying restored representative records..."
$AuditCount = podman exec -i $DbContainer psql -U $DbUser -d tantor -t -c "SELECT count(*) FROM audit_logs WHERE created_by='dr-test';"
if ([int]($AuditCount.Trim()) -lt 1) { throw "Data validation failed: Seeded records were not restored!" }

# 10. Exercise application operation
Write-Host "10. Exercising application operation (Server Healthcheck)..."
$ServerHealth = podman exec -i $ServerContainer curl -s http://localhost:8443/api/v1/monitoring/health | ConvertFrom-Json
if ($ServerHealth.status -ne "UP") { throw "Server health check failed after restore!" }

# 11 & 12. Measure RTO
$RestoreEndTime = Get-Date
$Rto = $RestoreEndTime - $RestoreStartTime

Write-Host "=========================================="
Write-Host " DR DRILL COMPLETED SUCCESSFULLY          "
Write-Host " Measured RTO: $($Rto.TotalSeconds) seconds"
Write-Host "=========================================="

Write-Host "Cleaning up DR environment..."
podman rm -f $ServerContainer $RepoContainer $DbContainer 2>$null
podman network rm $Network 2>$null
Remove-Item -Recurse -Force ".\dr-test-backups"
