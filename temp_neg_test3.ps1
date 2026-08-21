$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path temp_test3 | Out-Null
Copy-Item -Path versions.yaml -Destination temp_test3\versions.yaml
Copy-Item -Path scripts\test-m05-version-management.ps1 -Destination temp_test3\test.ps1
Copy-Item -Path tantor-agent\go.mod -Destination temp_test3\go.mod

$c = Get-Content temp_test3\versions.yaml
$c = $c -replace 'go: 1.22.0', 'go: 1.23.0'
Set-Content temp_test3\versions.yaml -Value $c

$s = Get-Content temp_test3\test.ps1
$s = $s -replace '\$RootDir = .*', '$RootDir = $PSScriptRoot'
Set-Content temp_test3\test.ps1 -Value $s

try {
    & .\temp_test3\test.ps1
    Write-Host 'FAILURE: Script should have thrown an error but passed.'
} catch {
    Write-Host 'SUCCESS: Script caught the mismatch! Error was: ' $_.Exception.Message
}
Remove-Item -Recurse -Force temp_test3
