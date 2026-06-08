# start-backend.ps1 - Load .env and start services

Write-Host "Loading environment variables from .env..." -ForegroundColor Cyan
if (Test-Path ".env") {
    foreach ($line in Get-Content ".env") {
        if (![string]::IsNullOrWhiteSpace($line) -and !$line.StartsWith("#")) {
            $name, $value = $line.Split('=', 2)
            Set-Item -Path env:\$name -Value $value
        }
    }
    Write-Host "Loaded successfully!" -ForegroundColor Green
} else {
    Write-Host "No .env file found!" -ForegroundColor Red
}

Write-Host "`nStarting Artifact Repository on port 8081..." -ForegroundColor Magenta
Start-Process -NoNewWindow -FilePath "java" -ArgumentList "-jar tantor-artifact-repository\tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"

Write-Host "Starting Management Server on port 8443..." -ForegroundColor Magenta
Start-Process -NoNewWindow -FilePath "java" -ArgumentList "-jar tantor-server\target\tantor-server-1.0.0.jar"

Write-Host "`nBoth services are starting! Check the console logs above." -ForegroundColor Green
