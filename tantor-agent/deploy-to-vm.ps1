param (
    [string]$VmIp = "192.168.3.149", # Change this to whatever VM you want to deploy to
    [string]$VmUser = "root",
    [string]$AgentDir = "/srv/tantor-agent"
)

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Tantor Agent Automated Deployment Script" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/4] Compiling latest code for Linux..." -ForegroundColor Yellow
$env:GOOS="linux"
$env:GOARCH="amd64"
go build -o tantor-agent-linux ./cmd/agent
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed! Aborting deployment." -ForegroundColor Red
    exit 1
}

Write-Host "[2/4] Stopping existing agent on $VmIp..." -ForegroundColor Yellow
# Remove legacy services/processes before installing one canonical service.
$removePreviousAgent = @"
set -Eeuo pipefail
systemctl disable --now tantor-agent.service 2>/dev/null || true
systemctl disable --now tantor-agent-linux.service 2>/dev/null || true
systemctl disable --now tantor-agent-new.service 2>/dev/null || true
rm -f /etc/systemd/system/tantor-agent.service
rm -f /etc/systemd/system/tantor-agent-linux.service
rm -f /etc/systemd/system/tantor-agent-new.service
rm -rf /etc/systemd/system/tantor-agent.service.d
rm -rf /etc/systemd/system/tantor-agent-linux.service.d
rm -rf /etc/systemd/system/tantor-agent-new.service.d
pkill -f '^(.*/)?tantor-agent(-linux|-new)?( |$)' 2>/dev/null || true
systemctl daemon-reload
systemctl reset-failed 2>/dev/null || true
sleep 2
exit 0
"@
$removePreviousAgent = $removePreviousAgent -replace "`r`n", "`n"
$removePreviousAgentBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($removePreviousAgent))
ssh "${VmUser}@${VmIp}" "printf '%s' '${removePreviousAgentBase64}' | base64 --decode | bash"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Legacy cleanup returned a warning; verifying SSH connectivity..." -ForegroundColor Yellow
    ssh "${VmUser}@${VmIp}" "true"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Cannot reconnect to $VmIp after stopping the previous agent." -ForegroundColor Red
        exit 1
    }
}

Write-Host "[3/4] Uploading new agent binary..." -ForegroundColor Yellow
ssh "${VmUser}@${VmIp}" "mkdir -p ${AgentDir}"
scp tantor-agent-linux "${VmUser}@${VmIp}:${AgentDir}/"
scp configs\agent.yaml "${VmUser}@${VmIp}:${AgentDir}/"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload failed! Aborting deployment." -ForegroundColor Red
    exit 1
}

Write-Host "[4/4] Creating directories & starting agent..." -ForegroundColor Yellow
$launcherContent = @"
#!/bin/bash
JAVA17_BIN=""
while IFS= read -r candidate; do
  if "`$candidate" -version 2>&1 | head -n 1 | grep -qE 'version "17\.'; then
    JAVA17_BIN="`$candidate"
    break
  fi
done < <(find /usr/lib/jvm -maxdepth 4 \( -type f -o -type l \) -path '*/bin/java' 2>/dev/null)
if [ -n "`$JAVA17_BIN" ]; then
  export JAVA_HOME="`$(dirname "`$(dirname "`$JAVA17_BIN")")"
  export PATH="`$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin"
else
  echo "WARN: Java 17 was not found under /usr/lib/jvm; using the system Java" >&2
fi
exec ${AgentDir}/tantor-agent-linux -config ${AgentDir}/agent.yaml >> ${AgentDir}/agent.log 2>&1
"@
$serviceContent = @"
[Unit]
Description=Tantor Kafka Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${AgentDir}
LimitNOFILE=1024000
LimitNPROC=1024000
ExecStart=${AgentDir}/run-agent.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
"@
$launcherContent = $launcherContent -replace "`r`n", "`n"
$serviceContent = $serviceContent -replace "`r`n", "`n"
$launcherBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($launcherContent))
$serviceBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($serviceContent))
$startCommand = @"
set -Eeuo pipefail
trap 'rc=`$?; echo "Agent deployment failed at remote line `$LINENO (exit `$rc)" >&2' ERR
systemctl stop tantor-agent.service 2>/dev/null || true
pkill -f '^(.*/)?tantor-agent(-linux|-new)?( |$)' 2>/dev/null || true
sleep 2
mkdir -p ${AgentDir}/data
mkdir -p ${AgentDir}/artifacts
mkdir -p ${AgentDir}/logs
chmod +x ${AgentDir}/tantor-agent-linux
printf '%s' '${launcherBase64}' | base64 --decode > ${AgentDir}/run-agent.sh
chmod 0755 ${AgentDir}/run-agent.sh
printf '%s' '${serviceBase64}' | base64 --decode > /etc/systemd/system/tantor-agent.service
test -s ${AgentDir}/run-agent.sh || exit 1
test -s /etc/systemd/system/tantor-agent.service || exit 1
systemctl daemon-reload
systemctl enable --now tantor-agent.service
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  systemctl is-active --quiet tantor-agent.service && break
  sleep 1
done
systemctl is-active --quiet tantor-agent.service
"@
$startCommand = $startCommand -replace "`r`n", "`n"
$startCommandBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($startCommand))
ssh "${VmUser}@${VmIp}" "printf '%s' '${startCommandBase64}' | base64 --decode | bash"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Agent service failed to start!" -ForegroundColor Red
    ssh "${VmUser}@${VmIp}" "ls -l /etc/systemd/system/tantor-agent.service ${AgentDir}/run-agent.sh 2>/dev/null || true; systemctl status tantor-agent.service --no-pager || true; journalctl -u tantor-agent.service -n 50 --no-pager || true; tail -n 50 ${AgentDir}/agent.log 2>/dev/null || true"
    exit 1
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  SUCCESS! Agent deployed and running!" -ForegroundColor Green
Write-Host "  To view live logs, SSH into the VM and run:" -ForegroundColor Green
Write-Host "  tail -f ${AgentDir}/agent.log" -ForegroundColor Cyan
Write-Host "  Service status: systemctl status tantor-agent" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Green
