param (
    [string]$VmIp = "192.168.3.208", # Change this to whatever VM you want to deploy to
    [string]$VmUser = "root",
    [string]$AgentDir = "/srv/tantor"
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
# We kill the agent first so we can overwrite the binary without getting a 'text file busy' error
ssh "${VmUser}@${VmIp}" "pkill -f 'tantor-agent-linux' || true; sleep 2"

Write-Host "[3/4] Uploading new agent binary..." -ForegroundColor Yellow
ssh "${VmUser}@${VmIp}" "mkdir -p ${AgentDir}"
scp tantor-agent-linux "${VmUser}@${VmIp}:${AgentDir}/"
scp configs\agent.yaml "${VmUser}@${VmIp}:${AgentDir}/"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload failed! Aborting deployment." -ForegroundColor Red
    exit 1
}

Write-Host "[4/4] Creating directories & starting agent..." -ForegroundColor Yellow
$startCommand = @"
mkdir -p ${AgentDir}/data
mkdir -p ${AgentDir}/artifacts
mkdir -p ${AgentDir}/logs
cd ${AgentDir}
chmod +x tantor-agent-linux
nohup ./tantor-agent-linux -config agent.yaml > agent.log 2>&1 &
"@
ssh "${VmUser}@${VmIp}" $startCommand

Write-Host ""
Write-Host "==========================================" -ForegroundColor Green
Write-Host "  SUCCESS! Agent deployed and running!" -ForegroundColor Green
Write-Host "  To view live logs, SSH into the VM and run:" -ForegroundColor Green
Write-Host "  tail -f ${AgentDir}/agent.log" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Green
