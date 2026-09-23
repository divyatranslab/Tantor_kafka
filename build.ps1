# build.ps1 - Automated Build Script for Tantor Java Backends

$ErrorActionPreference = 'Stop'

$MavenVersion = "3.9.6"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
$MavenZip = "$PSScriptRoot\apache-maven.zip"
$MavenDir = "$PSScriptRoot\apache-maven-$MavenVersion"
$MvnCmd = "$MavenDir\bin\mvn.cmd"

# Set JAVA_HOME to the installed JDK 21 if not already set or if invalid
$candidateJavaHome1 = "C:\Program Files\Java\jdk-21"
$candidateJavaHome2 = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
$candidateJavaHome3 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    if (Test-Path $candidateJavaHome1) {
        $env:JAVA_HOME = $candidateJavaHome1
    } elseif (Test-Path $candidateJavaHome2) {
        $env:JAVA_HOME = $candidateJavaHome2
    } elseif (Test-Path $candidateJavaHome3) {
        $env:JAVA_HOME = $candidateJavaHome3
    }
}
if (![string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
}

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
& $MvnCmd clean verify
if ($LASTEXITCODE -ne 0) { throw "Artifact Repository verification failed." }

# 3. Build Management Server
Write-Host "`n=== Building Management Server ===" -ForegroundColor Magenta
cd "$PSScriptRoot\tantor-server"
& $MvnCmd clean verify
if ($LASTEXITCODE -ne 0) { throw "Management Server verification failed." }

# Restore original directory
cd $PSScriptRoot

# 4. Build Agent (Linux amd64)
Write-Host "`n=== Building Tantor Agent (Linux) ===" -ForegroundColor Magenta
if (Test-Path "$PSScriptRoot\go\bin\go.exe") {
    cd "$PSScriptRoot\tantor-agent"
    $env:GOOS="linux"
    $env:GOARCH="amd64"
    & "$PSScriptRoot\go\bin\go.exe" test ./...
    if ($LASTEXITCODE -ne 0) { throw "Tantor Agent tests failed." }
    & "$PSScriptRoot\go\bin\go.exe" build -o tantor-agent-linux cmd/agent/main.go
    if ($LASTEXITCODE -ne 0) { throw "Tantor Agent build failed." }
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
    & "$PSScriptRoot\go\bin\go.exe" test ./...
    if ($LASTEXITCODE -ne 0) { throw "Tantor Discovery Agent tests failed." }
    & "$PSScriptRoot\go\bin\go.exe" build -o tantor-discovery-agent-linux .
    if ($LASTEXITCODE -ne 0) { throw "Tantor Discovery Agent build failed." }
    Write-Host "Discovery agent successfully compiled to: tantor-discovery-agent\tantor-discovery-agent-linux" -ForegroundColor Green
    cd $PSScriptRoot
} else {
    Write-Host "Go compiler not found in the 'go' directory. Skipping discovery agent compilation." -ForegroundColor Yellow
}

Write-Host "`nBuild Complete!" -ForegroundColor Green
Write-Host "To start the Artifact Repository:"
Write-Host "  java -jar tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"
Write-Host "To start the Management Server:"
Write-Host "  java -jar tantor-server\target\tantor-server-1.0.0.jar"
