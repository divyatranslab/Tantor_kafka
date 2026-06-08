# build.ps1 - Automated Build Script for Tantor Java Backends

$MavenVersion = "3.9.6"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
$MavenZip = "$PSScriptRoot\apache-maven.zip"
$MavenDir = "$PSScriptRoot\apache-maven-$MavenVersion"
$MvnCmd = "$MavenDir\bin\mvn.cmd"

# 1. Download Maven if not exists
if (-Not (Test-Path $MvnCmd)) {
    Write-Host "Maven not found. Downloading Apache Maven $MavenVersion..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $MavenUrl -OutFile $MavenZip
    Write-Host "Extracting Maven..." -ForegroundColor Cyan
    Expand-Archive -Path $MavenZip -DestinationPath $PSScriptRoot -Force
    Remove-Item $MavenZip
}

Write-Host "Using Maven at $MvnCmd" -ForegroundColor Green

# 2. Build Artifact Repository
Write-Host "`n=== Building Artifact Repository ===" -ForegroundColor Magenta
cd "$PSScriptRoot\tantor-artifact-repository"
& $MvnCmd clean package -DskipTests

# 3. Build Management Server
Write-Host "`n=== Building Management Server ===" -ForegroundColor Magenta
cd "$PSScriptRoot\tantor-server"
& $MvnCmd clean package -DskipTests

Write-Host "`nBuild Complete!" -ForegroundColor Green
Write-Host "To start the Artifact Repository:"
Write-Host "  java -jar tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"
Write-Host "To start the Management Server:"
Write-Host "  java -jar tantor-server\target\tantor-server-1.0.0.jar"
