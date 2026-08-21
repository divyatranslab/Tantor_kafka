<#
.SYNOPSIS
Backs up the Tantor PostgreSQL database.

.DESCRIPTION
Executes a logical pg_dump against the running Tantor database container.
Backups are saved to the ./deploy/backups directory by default.
Calculates SHA256 checksums, logs success/failure status, and maintains retention.
#>

param (
    [string]$ContainerName = "tantor-database-1",
    [string]$BackupDir = ".\deploy\backups",
    [int]$RetentionDays = 7,
    [string]$DatabaseId = "tantor"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir | Out-Null
}

$LogFile = Join-Path $BackupDir "backup_status.log"

function Write-AuditLog {
    param([string]$Status, [string]$Message)
    $TimestampStr = Get-Date -Format "o"
    $LogEntry = "[$TimestampStr] [DB:$DatabaseId] STATUS=$Status - $Message"
    Write-Host $LogEntry
    Add-Content -Path $LogFile -Value $LogEntry
}

# Resolve credentials using secure M-01 mechanism
$DbUser = $env:TANTOR_DB_USER
if (-not $DbUser -and (Test-Path ".\secrets\TANTOR_DB_USER")) {
    $DbUser = Get-Content ".\secrets\TANTOR_DB_USER" -Raw
}
if (-not $DbUser) {
    Write-AuditLog -Status "FAILED" -Message "Could not resolve TANTOR_DB_USER from environment or ./secrets/"
    throw "Authentication failure: Missing DB User"
}

$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupFile = Join-Path $BackupDir "tantor_db_$Timestamp.dump"
$ChecksumFile = Join-Path $BackupDir "tantor_db_$Timestamp.sha256"

Write-AuditLog -Status "STARTED" -Message "Initiating logical backup to $BackupFile"

try {
    # Execute pg_dump inside the container and capture custom format
    cmd.exe /c "podman exec -i $ContainerName pg_dump -U $DbUser -F c $DatabaseId > `"$BackupFile`""
    
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }

    $FileSize = (Get-Item $BackupFile).Length
    if ($FileSize -lt 1024) {
        throw "Backup file is suspiciously small ($FileSize bytes)."
    }

    # Generate checksum
    $Checksum = (Get-FileHash -Path $BackupFile -Algorithm SHA256).Hash
    Set-Content -Path $ChecksumFile -Value "$Checksum  $($BackupFile | Split-Path -Leaf)"

    Write-AuditLog -Status "SUCCESS" -Message "Backup completed. Size: $([math]::Round($FileSize / 1MB, 2)) MB. Checksum: $Checksum"
} catch {
    Write-AuditLog -Status "FAILED" -Message "Backup failed: $_"
    if (Test-Path $BackupFile) { Remove-Item $BackupFile -Force }
    if (Test-Path $ChecksumFile) { Remove-Item $ChecksumFile -Force }
    exit 1
}

# Cleanup old backups
Write-AuditLog -Status "INFO" -Message "Enforcing $RetentionDays day retention policy..."
$OldBackups = Get-ChildItem -Path $BackupDir -Filter "tantor_db_*.dump" | Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$RetentionDays) }

foreach ($OldBackup in $OldBackups) {
    Write-AuditLog -Status "INFO" -Message "Deleting expired backup: $($OldBackup.Name)"
    Remove-Item $OldBackup.FullName -Force
    $OldChecksum = $OldBackup.FullName -replace "\.dump$", ".sha256"
    if (Test-Path $OldChecksum) { Remove-Item $OldChecksum -Force }
}

Write-AuditLog -Status "INFO" -Message "Backup process finished successfully."
