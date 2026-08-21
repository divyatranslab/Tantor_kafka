<#
.SYNOPSIS
Restores the Tantor PostgreSQL database from a logical backup.

.DESCRIPTION
Executes pg_restore against the specified container. Includes guardrails 
to prevent accidental restoration over an initialized production database unless -Force is used.
Validates checksum if present.
#>

param (
    [Parameter(Mandatory=$true)]
    [string]$BackupFile,
    
    [string]$ContainerName = "tantor-database-1",
    [string]$DatabaseId = "tantor",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupFile)) {
    throw "Backup file not found: $BackupFile"
}

$ChecksumFile = $BackupFile -replace "\.dump$", ".sha256"
if (Test-Path $ChecksumFile) {
    Write-Host "Verifying checksum..."
    $ExpectedLine = Get-Content $ChecksumFile | Select-Object -First 1
    $ExpectedHash = $ExpectedLine.Split(" ")[0]
    $ActualHash = (Get-FileHash -Path $BackupFile -Algorithm SHA256).Hash
    
    if ($ExpectedHash -ne $ActualHash) {
        throw "Checksum validation failed! Expected: $ExpectedHash, Actual: $ActualHash"
    }
    Write-Host "Checksum validated successfully."
} else {
    Write-Warning "No checksum file found. Proceeding without integrity check."
}

# Resolve credentials using secure M-01 mechanism
$DbUser = $env:TANTOR_DB_USER
if (-not $DbUser -and (Test-Path ".\secrets\TANTOR_DB_USER")) {
    $DbUser = Get-Content ".\secrets\TANTOR_DB_USER" -Raw
}
if (-not $DbUser) {
    throw "Authentication failure: Missing DB User"
}

# Guardrail: Check if database has tables
$CheckCmd = "podman exec -i $ContainerName psql -U $DbUser -d $DatabaseId -t -c `"SELECT count(*) FROM information_schema.tables WHERE table_schema='public';`""
$TableCountStr = cmd.exe /c $CheckCmd
$TableCount = [int]($TableCountStr.Trim())

if ($TableCount -gt 0 -and -not $Force) {
    throw "Database '$DatabaseId' is not empty (Found $TableCount tables). Use -Force to overwrite."
}

if ($Force) {
    Write-Warning "Force flag enabled. This will overwrite existing data in the database!"
}

Write-Host "Restoring database from $BackupFile to container $ContainerName..."

try {
    # Use podman cp to copy the dump file into the container temporarily for faster/reliable restore
    # Wait, we can just pipe it via stdin.
    
    # pg_restore -c (clean/drop before create), -1 (single transaction)
    $RestoreCmd = "podman exec -i $ContainerName pg_restore -U $DbUser -d $DatabaseId -c -1"
    
    # Run the command and pipe the file content
    cmd.exe /c "type `"$BackupFile`" | $RestoreCmd"
    
    if ($LASTEXITCODE -ne 0) {
        throw "pg_restore failed with exit code $LASTEXITCODE"
    }

    Write-Host "Restore command completed successfully. Validating schema..."
    
    # Validate tables exist post-restore
    $PostTableCountStr = cmd.exe /c $CheckCmd
    $PostTableCount = [int]($PostTableCountStr.Trim())
    
    if ($PostTableCount -eq 0) {
        throw "Validation failed: Database is empty after restore!"
    }

    Write-Host "Restore validated successfully. ($PostTableCount tables present)."
} catch {
    Write-Error "Restore failed: $_"
    exit 1
}
