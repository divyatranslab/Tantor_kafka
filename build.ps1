# build.ps1 - Automated Build Script for Tantor Java Backends

$MavenVersion = "3.9.6"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
$MavenZip = "$PSScriptRoot\apache-maven.zip"
$MavenDir = "$PSScriptRoot\apache-maven-$MavenVersion"
$MvnCmd = "$MavenDir\bin\mvn.cmd"

# Set JAVA_HOME to the installed JDK 21
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path

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

# Restore original directory
cd $PSScriptRoot

Write-Host "`nBuild Complete!" -ForegroundColor Green
Write-Host "To start the Artifact Repository:"
Write-Host "  java -jar tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"
Write-Host "To start the Management Server:"
Write-Host "  java -jar tantor-server\target\tantor-server-1.0.0.jar"

# 4. Build Agent (Linux amd64)
Write-Host "`n=== Building Tantor Agent (Linux) ===" -ForegroundColor Magenta
if (Test-Path "$PSScriptRoot\go\bin\go.exe") {
    cd "$PSScriptRoot\tantor-agent"
    $env:GOOS="linux"
    $env:GOARCH="amd64"
    & "$PSScriptRoot\go\bin\go.exe" build -o tantor-agent-linux cmd/agent/main.go
    Write-Host "Agent successfully compiled to: tantor-agent\tantor-agent-linux" -ForegroundColor Green
    cd $PSScriptRoot
} else {
    Write-Host "Go compiler not found in the 'go' directory. Skipping agent compilation." -ForegroundColor Yellow
}

# 5. Build Discovery Agent (Linux amd64)
Write-Host "`n=== Building Tantor Discovery Agent (Linux) ===" -ForegroundColor Magenta
if (Test-Path "$PSScriptRoot\go\bin\go.exe") {
    cd "$PSScriptRoot\tantor-discovery-agent"
    $env:GOOS="linux"
    $env:GOARCH="amd64"
    & "$PSScriptRoot\go\bin\go.exe" build -o tantor-discovery-agent-linux .
    Write-Host "Discovery agent successfully compiled to: tantor-discovery-agent\tantor-discovery-agent-linux" -ForegroundColor Green
    cd $PSScriptRoot
} else {
    Write-Host "Go compiler not found in the 'go' directory. Skipping discovery agent compilation." -ForegroundColor Yellow
}
