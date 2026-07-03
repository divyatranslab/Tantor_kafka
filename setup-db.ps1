$Password = Read-Host "Please enter your local PostgreSQL password for the 'postgres' user" -AsSecureString
$BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
$PlainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)

$env:PGPASSWORD = $PlainPassword

Write-Host "Creating 'tantor' database for both Tantor Server and Artifact Repository..."
psql -U postgres -c "CREATE DATABASE tantor;"

Write-Host "Updating your .env file with the password..."
$EnvPath = "$PSScriptRoot\.env"
if (Test-Path $EnvPath) {
    (Get-Content $EnvPath) -replace 'YOUR_POSTGRES_PASSWORD_HERE', $PlainPassword | Set-Content $EnvPath
}

Write-Host "Done! You can now run .\start-backend-dev.ps1" -ForegroundColor Green
