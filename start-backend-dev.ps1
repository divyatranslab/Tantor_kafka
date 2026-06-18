# start-backend-dev.ps1 - Run Tantor backend services in foreground with labeled, color-coded logs.
# Usage: .\start-backend-dev.ps1
# Press Ctrl+C to stop all services.

[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$RootDir = $PSScriptRoot

# ── Load .env ────────────────────────────────────────────────────────────────
$envFile = Join-Path $RootDir ".env"
if (Test-Path $envFile) {
    Write-Host "Loading environment variables from .env..." -ForegroundColor Cyan
    foreach ($line in Get-Content $envFile) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#") -or !$trimmed.Contains("=")) { continue }
        $name, $value = $trimmed.Split("=", 2)
        $name  = $name.Trim()
        $value = $value.Trim().Trim('"').Trim("'")
        if (![string]::IsNullOrWhiteSpace($name)) { Set-Item -Path "env:$name" -Value $value }
    }
}

# ── Resolve Java ─────────────────────────────────────────────────────────────
$candidateJavaHome = "C:\Program Files\Java\jdk-21"
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path $candidateJavaHome)) {
    $env:JAVA_HOME = $candidateJavaHome
}
if (![string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
$JavaExe = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (!$JavaExe) { throw "Java not found. Install JDK 21 or set JAVA_HOME." }

# ── Locate JARs ─────────────────────────────────────────────────────────────
$services = @(
    @{
        Name    = "ARTIFACT"
        Display = "Tantor Artifact Repository"
        Color   = "Magenta"
        Jar     = Join-Path $RootDir "tantor-artifact-repository\target\tantor-artifact-repository-1.0.0.jar"
    },
    @{
        Name    = "SERVER"
        Display = "Tantor Management Server"
        Color   = "Green"
        Jar     = Join-Path $RootDir "tantor-server\target\tantor-server-1.0.0.jar"
    }
)

foreach ($svc in $services) {
    if (!(Test-Path $svc.Jar)) {
        throw "$($svc.Display) JAR not found at $($svc.Jar). Run .\build.ps1 first."
    }
}

# ── Stop any already-running Tantor Java processes ───────────────────────────
try {
    $existingJava = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $cmd = $_.CommandLine
            $cmd -and ($cmd -like "*tantor-artifact-repository*" -or $cmd -like "*tantor-server*")
        }
    foreach ($proc in $existingJava) {
        Write-Host "Stopping existing Tantor process PID $($proc.ProcessId)..." -ForegroundColor Yellow
        Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($existingJava) { Start-Sleep -Seconds 2 }
} catch {
    Write-Host "Warning: Could not check for existing processes: $($_.Exception.Message)" -ForegroundColor Yellow
}

# ── Banner ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║     Tantor Backend — Development Mode            ║" -ForegroundColor Cyan
Write-Host "║     Press Ctrl+C to stop all services            ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# ── Start processes with async output events ─────────────────────────────────
$processes = @()
$eventIds  = @()

foreach ($svc in $services) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $JavaExe
    $psi.Arguments              = "-jar `"$($svc.Jar)`""
    $psi.WorkingDirectory       = $RootDir
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.CreateNoWindow         = $true

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $proc.EnableRaisingEvents = $true

    # Register events (no -Action; we process them on the main thread)
    $outId = "$($svc.Name)_OUT"
    $errId = "$($svc.Name)_ERR"
    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -SourceIdentifier $outId | Out-Null
    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived  -SourceIdentifier $errId | Out-Null
    $eventIds += $outId, $errId

    $proc.Start() | Out-Null
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()

    $processes += $proc
    Write-Host "[$($svc.Name)] $($svc.Display) started — PID $($proc.Id)" -ForegroundColor ([ConsoleColor]$svc.Color)
}

Write-Host ""

# ── Build a lookup for prefix/color by source identifier ─────────────────────
$prefixMap = @{}
foreach ($svc in $services) {
    $prefixMap["$($svc.Name)_OUT"] = $svc
    $prefixMap["$($svc.Name)_ERR"] = $svc
}

# ── Main loop: drain events and print labelled lines ─────────────────────────
try {
    while ($true) {
        # Drain all queued events
        while ($true) {
            $ev = Get-Event -ErrorAction SilentlyContinue
            if (!$ev) { break }

            $line = $ev.SourceEventArgs.Data
            if (![string]::IsNullOrEmpty($line)) {
                $svc = $prefixMap[$ev.SourceIdentifier]
                if ($svc) {
                    $pad = $svc.Name.PadRight(8)
                    Write-Host "[$pad] " -ForegroundColor ([ConsoleColor]$svc.Color) -NoNewline
                    Write-Host $line
                } else {
                    Write-Host $line
                }
            }
            Remove-Event -EventIdentifier $ev.EventIdentifier
        }

        # Check if both processes have exited
        $allExited = $true
        foreach ($proc in $processes) {
            if (!$proc.HasExited) { $allExited = $false; break }
        }
        if ($allExited) {
            Write-Host ""
            for ($i = 0; $i -lt $services.Count; $i++) {
                $svc  = $services[$i]
                $proc = $processes[$i]
                $code = $proc.ExitCode
                $color = if ($code -eq 0) { "Green" } else { "Red" }
                Write-Host "[$($svc.Name)] $($svc.Display) exited with code $code" -ForegroundColor $color
            }
            break
        }

        Start-Sleep -Milliseconds 100
    }
}
finally {
    Write-Host "`nShutting down..." -ForegroundColor Yellow

    foreach ($id in $eventIds) {
        Unregister-Event -SourceIdentifier $id -ErrorAction SilentlyContinue
    }

    foreach ($i in 0..($processes.Count - 1)) {
        $proc = $processes[$i]
        $svc  = $services[$i]
        if ($proc -and !$proc.HasExited) {
            Write-Host "Stopping $($svc.Display) (PID $($proc.Id))..." -ForegroundColor Yellow
            try { $proc.Kill() } catch {}
            $proc.WaitForExit(5000) | Out-Null
        }
        if ($proc) { $proc.Dispose() }
    }

    Write-Host "All services stopped." -ForegroundColor Green
}
