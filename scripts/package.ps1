# AgentCLI 打包脚本（Windows PowerShell）
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "Building AgentCLI fat-jar..."
mvn -q clean package -DskipTests=false

$jar = Join-Path (Get-Location) "target\agentcli-1.0.0.jar"
if (-not (Test-Path $jar)) {
    throw "Build failed: $jar not found"
}

Write-Host "Done: $jar"
Write-Host "Run: java -jar target\agentcli-1.0.0.jar --help"
