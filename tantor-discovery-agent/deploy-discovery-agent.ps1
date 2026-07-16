param (
    [Parameter(Mandatory = $true)]
    [string]$VmIp,

    [Parameter(Mandatory = $true)]
    [string]$ServerUrl,

    [string]$SshUser = "root",
    [int]$SshPort = 22,
    [string]$SshKeyPath = "",

    [string]$RuntimeUser = "tantor",
    [string]$AgentDir = "/opt/tantor-discovery-agent",
    [string]$ServiceName = "tantor-discovery-agent",

    [string]$HostId = "",
    [string]$AgentName = "",
    [string]$NodeName = "",
    [string]$KafkaHome = "",
    [string[]]$KafkaConfigFiles = @(),
    [string]$KafkaDataDirs = "",
    [string]$KafkaLogDirs = "",
    [string[]]$ScanPaths = @("/opt", "/opt_apb", "/app", "/srv", "/data", "/usr/local", "/usr/share", "/var/lib"),
    [string]$Interval = "15s",
    [string]$TaskPollInterval = "5s",
    [string]$KafkaServiceName = "kafka.service",
    [string]$RestartCommand = "",
    [string]$MetricsUrl = "http://localhost:7071/metrics",

    [switch]$DisableMetrics,
    [switch]$SkipPrecheck,
    [switch]$TlsVerify,
    [switch]$SystemdUseSudo,
    [switch]$InstallSudoers,
    [switch]$SkipBuild,
    [switch]$AllowModuleDownload,

    [string]$BinaryPath = ".\tantor-discovery-agent-linux",
    [string]$GoExe = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location $PSScriptRoot
try {
    function Write-Step([string]$Message) {
        Write-Host $Message -ForegroundColor Yellow
    }

    function Bool-Lower([bool]$Value) {
        return $Value.ToString().ToLowerInvariant()
    }

    function Yaml-Quote([string]$Value) {
        if ($null -eq $Value) { $Value = "" }
        $escaped = $Value.Replace("\", "\\").Replace('"', '\"')
        return '"' + $escaped + '"'
    }

    function Bash-Quote([string]$Value) {
        if ($null -eq $Value) { $Value = "" }
        return "'" + $Value.Replace("'", "'`"`"'" ) + "'"
    }

    function Resolve-GoExe {
        if ($GoExe) { return $GoExe }
        $cmd = Get-Command go -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
        $bundled = Join-Path $PSScriptRoot "..\go\bin\go.exe"
        if (Test-Path $bundled) { return (Resolve-Path $bundled).Path }
        throw "Go executable not found. Pass -GoExe or use -SkipBuild with a prebuilt Linux binary."
    }

    function Invoke-RemoteScript([string]$Script) {
        $normalized = $Script -replace "`r`n", "`n"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($normalized))
        $sshArgs = @()
        if ($SshPort -ne 22) { $sshArgs += @("-p", "$SshPort") }
        if ($SshKeyPath) { $sshArgs += @("-i", $SshKeyPath) }
        $sshArgs += @("${SshUser}@${VmIp}", "printf '%s' '$encoded' | base64 --decode | bash")
        & ssh @sshArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Remote command failed on $VmIp."
        }
    }

    function Copy-ToRemote([string]$LocalPath, [string]$RemotePath) {
        $scpArgs = @()
        if ($SshPort -ne 22) { $scpArgs += @("-P", "$SshPort") }
        if ($SshKeyPath) { $scpArgs += @("-i", $SshKeyPath) }
        $scpArgs += @($LocalPath, "${SshUser}@${VmIp}:$RemotePath")
        & scp @scpArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to upload $LocalPath to $VmIp."
        }
    }

    if (-not $HostId) {
        $HostId = "discovery-" + ($VmIp -replace "[^A-Za-z0-9_-]", "-")
    }
    if (-not $AgentName) {
        $AgentName = "tantor-discovery-" + ($VmIp -replace "[^A-Za-z0-9_-]", "-")
    }
    $effectiveSystemdUseSudo = $SystemdUseSudo.IsPresent -or ($RuntimeUser -ne "root")
    if (-not $RestartCommand) {
        $RestartCommand = if ($effectiveSystemdUseSudo) { "sudo systemctl restart $KafkaServiceName" } else { "systemctl restart $KafkaServiceName" }
    }

    Write-Host "=================================================" -ForegroundColor Cyan
    Write-Host "  Tantor Discovery Agent Air-Gapped Deployment   " -ForegroundColor Cyan
    Write-Host "=================================================" -ForegroundColor Cyan
    Write-Host "Target VM      : $VmIp"
    Write-Host "Tantor Server  : $ServerUrl"
    Write-Host "SSH User       : $SshUser"
    Write-Host "Runtime User   : $RuntimeUser"
    Write-Host "Install Dir    : $AgentDir"
    Write-Host ""

    if (-not $SkipBuild) {
        Write-Step "[1/5] Building static Linux binary locally..."
        $resolvedGo = Resolve-GoExe
        $env:GOOS = "linux"
        $env:GOARCH = "amd64"
        $env:CGO_ENABLED = "0"
        if (-not $AllowModuleDownload.IsPresent) {
            $env:GOPROXY = "off"
            $env:GOSUMDB = "off"
        }

        $buildArgs = @("build", "-trimpath")
        if (Test-Path "vendor") {
            $buildArgs += "-mod=vendor"
        }
        $buildArgs += @("-ldflags", "-s -w", "-o", $BinaryPath, ".")
        & $resolvedGo @buildArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Go build failed."
        }
    } else {
        Write-Step "[1/5] Skipping build and using existing binary..."
    }

    if (-not (Test-Path $BinaryPath)) {
        throw "Binary not found: $BinaryPath"
    }

    Write-Step "[2/5] Generating target-specific discovery.yaml..."
    $scanPathYaml = ($ScanPaths | ForEach-Object { "    - " + (Yaml-Quote $_) }) -join "`n"
    $kafkaConfigYaml = ($KafkaConfigFiles | ForEach-Object { "    - " + (Yaml-Quote $_) }) -join "`n"
    $configContent = @"
discovery:
  host_id: $(Yaml-Quote $HostId)
  agent_name: $(Yaml-Quote $AgentName)
  server_url: $(Yaml-Quote $ServerUrl)
  interval: $(Yaml-Quote $Interval)
  task_poll_interval: $(Yaml-Quote $TaskPollInterval)
  kafka_home: $(Yaml-Quote $KafkaHome)
  kafka_config_files:
$kafkaConfigYaml
  kafka_data_dirs: $(Yaml-Quote $KafkaDataDirs)
  kafka_log_dirs: $(Yaml-Quote $KafkaLogDirs)
  scan_paths:
$scanPathYaml
  node_name: $(Yaml-Quote $NodeName)
  restart_command: $(Yaml-Quote $RestartCommand)
  systemd_use_sudo: $(Bool-Lower $effectiveSystemdUseSudo)
  metrics_url: $(Yaml-Quote $MetricsUrl)
  disable_metrics: $(Bool-Lower $DisableMetrics.IsPresent)
  skip_precheck: $(Bool-Lower $SkipPrecheck.IsPresent)
  tls_insecure_skip_verify: $(Bool-Lower (-not $TlsVerify.IsPresent))
"@

    $localConfig = Join-Path ([IO.Path]::GetTempPath()) ("tantor-discovery-" + [Guid]::NewGuid().ToString("N") + ".yaml")
    Set-Content -Path $localConfig -Value $configContent -Encoding UTF8

    $remoteTmp = "/tmp/tantor-discovery-agent-" + [Guid]::NewGuid().ToString("N")
    $remoteTmpQ = Bash-Quote $remoteTmp

    Write-Step "[3/5] Preparing remote staging directory..."
    Invoke-RemoteScript @"
set -Eeuo pipefail
rm -rf $remoteTmpQ
mkdir -p $remoteTmpQ
"@

    Write-Step "[4/5] Uploading binary and generated config..."
    Copy-ToRemote $BinaryPath "$remoteTmp/tantor-discovery-agent-linux"
    Copy-ToRemote $localConfig "$remoteTmp/discovery.yaml"

    $agentDirQ = Bash-Quote $AgentDir
    $runtimeUserQ = Bash-Quote $RuntimeUser
    $serviceNameQ = Bash-Quote $ServiceName
    $kafkaServiceNameQ = Bash-Quote $KafkaServiceName
    $installSudoers = Bool-Lower $InstallSudoers.IsPresent
    $systemdUseSudo = Bool-Lower $effectiveSystemdUseSudo

    $serviceContent = @"
[Unit]
Description=Tantor Discovery Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RuntimeUser
WorkingDirectory=$AgentDir
ExecStart=$AgentDir/tantor-discovery-agent-linux -config $AgentDir/discovery.yaml
Restart=always
RestartSec=5
LimitNOFILE=1024000
LimitNPROC=1024000

[Install]
WantedBy=multi-user.target
"@
    $serviceBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($serviceContent -replace "`r`n", "`n")))

    Write-Step "[5/5] Installing and starting systemd service..."
    Invoke-RemoteScript @"
set -Eeuo pipefail
AGENT_DIR=$agentDirQ
RUNTIME_USER=$runtimeUserQ
SERVICE_NAME=$serviceNameQ
KAFKA_SERVICE_NAME=$kafkaServiceNameQ
REMOTE_TMP=$remoteTmpQ
INSTALL_SUDOERS=$installSudoers
SYSTEMD_USE_SUDO=$systemdUseSudo

sudo systemctl disable --now "`$SERVICE_NAME.service" 2>/dev/null || true
sudo pkill -f '^(.*/)?tantor-discovery-agent(-linux)?( |$)' 2>/dev/null || true

if [ "`$RUNTIME_USER" != "root" ] && ! id "`$RUNTIME_USER" >/dev/null 2>&1; then
  sudo useradd --system --home-dir "`$AGENT_DIR" --shell /sbin/nologin "`$RUNTIME_USER"
fi

sudo mkdir -p "`$AGENT_DIR/logs"
sudo install -m 0755 "`$REMOTE_TMP/tantor-discovery-agent-linux" "`$AGENT_DIR/tantor-discovery-agent-linux"
sudo install -m 0640 "`$REMOTE_TMP/discovery.yaml" "`$AGENT_DIR/discovery.yaml"
sudo chown -R "`$RUNTIME_USER:`$RUNTIME_USER" "`$AGENT_DIR" 2>/dev/null || sudo chown -R "`$RUNTIME_USER" "`$AGENT_DIR"

printf '%s' '$serviceBase64' | base64 --decode | sudo tee "/etc/systemd/system/`$SERVICE_NAME.service" >/dev/null

if [ "`$INSTALL_SUDOERS" = "true" ] && [ "`$SYSTEMD_USE_SUDO" = "true" ] && [ "`$RUNTIME_USER" != "root" ]; then
  SUDOERS_FILE="/etc/sudoers.d/`$SERVICE_NAME"
  {
    echo "Defaults:`$RUNTIME_USER !requiretty"
    echo "`$RUNTIME_USER ALL=(root) NOPASSWD: /bin/systemctl restart `$KAFKA_SERVICE_NAME, /usr/bin/systemctl restart `$KAFKA_SERVICE_NAME, /bin/systemctl is-active --quiet `$KAFKA_SERVICE_NAME, /usr/bin/systemctl is-active --quiet `$KAFKA_SERVICE_NAME"
  } | sudo tee "`$SUDOERS_FILE" >/dev/null
  sudo chmod 0440 "`$SUDOERS_FILE"
  sudo visudo -cf "`$SUDOERS_FILE" >/dev/null
fi

sudo systemctl daemon-reload
sudo systemctl enable --now "`$SERVICE_NAME.service"
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  sudo systemctl is-active --quiet "`$SERVICE_NAME.service" && break
  sleep 1
done
sudo systemctl is-active --quiet "`$SERVICE_NAME.service"
rm -rf "`$REMOTE_TMP"
"@

    Remove-Item -LiteralPath $localConfig -Force -ErrorAction SilentlyContinue

    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Green
    Write-Host "  SUCCESS! Discovery Agent deployed." -ForegroundColor Green
    Write-Host "  Logs   : ssh $SshUser@$VmIp 'sudo journalctl -u $ServiceName -f'" -ForegroundColor Cyan
    Write-Host "  Config : $AgentDir/discovery.yaml" -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Green
} finally {
    Pop-Location
}
