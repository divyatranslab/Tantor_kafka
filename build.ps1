# build.ps1 - Automated Build Script for Tantor Java Backends

$ErrorActionPreference = "Stop"

$MavenVersion = "3.9.6"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
$MavenZip = "$PSScriptRoot\apache-maven.zip"
$MavenDir = "$PSScriptRoot\apache-maven-$MavenVersion"
$MvnCmd = "$MavenDir\bin\mvn.cmd"

# Set JAVA_HOME to the installed JDK 21 if not already set or if invalid
$candidateJavaHome1 = "C:\Program Files\Java\jdk-21"
$candidateJavaHome2 = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    if (Test-Path $candidateJavaHome1) {
        $env:JAVA_HOME = $candidateJavaHome1
    } elseif (Test-Path $candidateJavaHome2) {
        $env:JAVA_HOME = $candidateJavaHome2
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

# 1.5. Build Shared Security Library
Write-Host "`n=== Building Shared Security Library ===" -ForegroundColor Magenta
Push-Location "$PSScriptRoot\tantor-security"
try {
    & $MvnCmd clean install
    if ($LASTEXITCODE -ne 0) {
        throw "Shared security library build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

# 2. Build Artifact Repository
Write-Host "`n=== Building Artifact Repository ===" -ForegroundColor Magenta
Push-Location "$PSScriptRoot\tantor-artifact-repository"
try {
    & $MvnCmd clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Artifact repository build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

# 3. Build Management Server
Write-Host "`n=== Building Management Server ===" -ForegroundColor Magenta
Push-Location "$PSScriptRoot\tantor-server"
try {
    & $MvnCmd clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Management server build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

# 4. Build Agent (Linux amd64)
Write-Host "`n=== Building Tantor Agent (Linux) ===" -ForegroundColor Magenta
$GoCmd = "$PSScriptRoot\go\bin\go.exe"
if (Test-Path $GoCmd) {
    $AgentDir = "$PSScriptRoot\tantor-agent-production-ready"
    $AgentBuildDir = "$AgentDir\build"
    $AgentOutput = "$AgentBuildDir\tantor-agent-linux-amd64"
    New-Item -ItemType Directory -Force -Path $AgentBuildDir | Out-Null

    $PreviousGoOs = $env:GOOS
    $PreviousGoArch = $env:GOARCH
    try {
        $env:GOOS = "linux"
        $env:GOARCH = "amd64"
        Push-Location $AgentDir
        try {
            & $GoCmd build -trimpath -buildvcs=false -o $AgentOutput ./cmd/agent
            if ($LASTEXITCODE -ne 0) {
                throw "Tantor agent build failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
    } finally {
        if ($null -eq $PreviousGoOs) {
            Remove-Item Env:GOOS -ErrorAction SilentlyContinue
        } else {
            $env:GOOS = $PreviousGoOs
        }
        if ($null -eq $PreviousGoArch) {
            Remove-Item Env:GOARCH -ErrorAction SilentlyContinue
        } else {
            $env:GOARCH = $PreviousGoArch
        }
    }
    Write-Host "Agent successfully compiled to: $AgentOutput" -ForegroundColor Green
} else {
    Write-Host "Go compiler not found in the 'go' directory. Skipping agent compilation." -ForegroundColor Yellow
}

# 5. Build Discovery Agent (Linux amd64)
Write-Host "`n=== Building Tantor Discovery Agent (Linux) ===" -ForegroundColor Magenta
if (Test-Path $GoCmd) {
    $DiscoveryAgentDir = "$PSScriptRoot\tantor-agent-rhel8-rhel9-http-v3.1\source"
    $DiscoveryBuildDir = "$PSScriptRoot\tantor-agent-rhel8-rhel9-http-v3.1\build"
    $DiscoveryOutput = "$DiscoveryBuildDir\tantor-discovery-agent-linux-amd64"
    New-Item -ItemType Directory -Force -Path $DiscoveryBuildDir | Out-Null

    $PreviousGoOs = $env:GOOS
    $PreviousGoArch = $env:GOARCH
    try {
        $env:GOOS = "linux"
        $env:GOARCH = "amd64"
        Push-Location $DiscoveryAgentDir
        try {
            & $GoCmd build -trimpath -buildvcs=false -o $DiscoveryOutput .
            if ($LASTEXITCODE -ne 0) {
                throw "Tantor discovery agent build failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
    } finally {
        if ($null -eq $PreviousGoOs) {
            Remove-Item Env:GOOS -ErrorAction SilentlyContinue
        } else {
            $env:GOOS = $PreviousGoOs
        }
        if ($null -eq $PreviousGoArch) {
            Remove-Item Env:GOARCH -ErrorAction SilentlyContinue
        } else {
            $env:GOARCH = $PreviousGoArch
        }
    }
    Write-Host "Discovery agent successfully compiled to: $DiscoveryOutput" -ForegroundColor Green
} else {
    Write-Host "Go compiler not found in the 'go' directory. Skipping discovery agent compilation." -ForegroundColor Yellow
}

Write-Host "`n=== Build Complete ===" -ForegroundColor Green
Write-Host "Artifact Repository:"
Write-Host "  java -jar tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"
Write-Host "Management Server:"
Write-Host "  java -jar tantor-server\target\tantor-server-1.0.0.jar"
Write-Host "Tantor Agent:"
Write-Host "  tantor-agent-production-ready\build\tantor-agent-linux-amd64"
Write-Host "Discovery Agent:"
Write-Host "  tantor-agent-rhel8-rhel9-http-v3.1\build\tantor-discovery-agent-linux-amd64"
