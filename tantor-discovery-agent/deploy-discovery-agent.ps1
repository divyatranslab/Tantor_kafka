param (
    [string]$VmIp = "192.168.3.161", # Change this to whatever VM you want to deploy to
    [string]$VmUser = "root",
    [string]$AgentDir = "/tmp/tantor-discovery-agent"
)

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "  Tantor Discovery Agent Automated Deployment    " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/4] Compiling latest code for Linux..." -ForegroundColor Yellow
$env:GOOS="linux"
$env:GOARCH="amd64"
go build -o tantor-discovery-agent-linux .
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed! Aborting deployment." -ForegroundColor Red
    exit 1
}

Write-Host "[2/4] Stopping existing discovery agent on $VmIp..." -ForegroundColor Yellow
$removePreviousAgent = @"
set -Eeuo pipefail
sudo systemctl disable --now tantor-discovery-agent.service 2>/dev/null || true
sudo rm -f /etc/systemd/system/tantor-discovery-agent.service
sudo pkill -f '^(.*/)?tantor-discovery-agent(-linux)?( |$)' 2>/dev/null || true
sudo systemctl daemon-reload
sudo systemctl reset-failed 2>/dev/null || true
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
ssh "${VmUser}@${VmIp}" "sudo mkdir -p ${AgentDir} && sudo chown ${VmUser} ${AgentDir} && rm -f ${AgentDir}/tantor-discovery-agent-linux"
scp tantor-discovery-agent-linux "${VmUser}@${VmIp}:${AgentDir}/"
scp configs\discovery.yaml "${VmUser}@${VmIp}:${AgentDir}/"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload failed! Aborting deployment." -ForegroundColor Red
    exit 1
}

Write-Host "[4/4] Creating directories & starting agent..." -ForegroundColor Yellow
$launcherContent = @"
#!/bin/bash
exec ${AgentDir}/tantor-discovery-agent-linux -config ${AgentDir}/discovery.yaml >> ${AgentDir}/discovery-agent.log 2>&1
"@
$serviceContent = @"
[Unit]
Description=Tantor Discovery Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${AgentDir}
LimitNOFILE=1024000
LimitNPROC=1024000
ExecStart=${AgentDir}/run-discovery-agent.sh
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
sudo systemctl stop tantor-discovery-agent.service 2>/dev/null || true
sudo pkill -f '^(.*/)?tantor-discovery-agent(-linux)?( |$)' 2>/dev/null || true
sleep 2
sudo mkdir -p ${AgentDir}/logs
sudo chown -R ${VmUser} ${AgentDir}
chmod +x ${AgentDir}/tantor-discovery-agent-linux
printf '%s' '${launcherBase64}' | base64 --decode > ${AgentDir}/run-discovery-agent.sh
chmod 0755 ${AgentDir}/run-discovery-agent.sh
printf '%s' '${serviceBase64}' | base64 --decode | sudo tee /etc/systemd/system/tantor-discovery-agent.service > /dev/null
test -s ${AgentDir}/run-discovery-agent.sh || exit 1
test -s /etc/systemd/system/tantor-discovery-agent.service || exit 1
sudo systemctl daemon-reload
sudo systemctl enable --now tantor-discovery-agent.service
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  sudo systemctl is-active --quiet tantor-discovery-agent.service && break
  sleep 1
done
sudo systemctl is-active --quiet tantor-discovery-agent.service
"@
$startCommand = $startCommand -replace "`r`n", "`n"
$startCommandBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($startCommand))
ssh "${VmUser}@${VmIp}" "printf '%s' '${startCommandBase64}' | base64 --decode | bash"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Agent service failed to start!" -ForegroundColor Red
    ssh "${VmUser}@${VmIp}" "ls -l /etc/systemd/system/tantor-discovery-agent.service ${AgentDir}/run-discovery-agent.sh 2>/dev/null || true; sudo systemctl status tantor-discovery-agent.service --no-pager || true; sudo journalctl -u tantor-discovery-agent.service -n 50 --no-pager || true; tail -n 50 ${AgentDir}/discovery-agent.log 2>/dev/null || true"
    exit 1
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  SUCCESS! Discovery Agent deployed!" -ForegroundColor Green
Write-Host "  To view live logs, SSH into the VM and run:" -ForegroundColor Green
Write-Host "  tail -f ${AgentDir}/discovery-agent.log" -ForegroundColor Cyan
Write-Host "  Service status: systemctl status tantor-discovery-agent" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Green
