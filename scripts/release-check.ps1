# AgentCLI 发布质量检查脚本
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

Write-Host "==> [1/4] mvn clean package + tests"
mvn -q clean package -DskipTests=false -DforkCount=0
if ($LASTEXITCODE -ne 0) { throw "Build/test failed" }

Write-Host "==> [2/4] jar smoke test"
$jar = "target\agentcli-1.0.0.jar"
if (-not (Test-Path $jar)) { throw "Missing $jar" }
java -jar $jar --version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Jar --version failed" }

Write-Host "==> [3/4] legacy identifier scan"
$patterns = @("com.paicli", "paicli_", "~/.paicli", " paicli ")
$roots = @("src", "README.md", "pom.xml", ".env.example", "Dockerfile")
$hits = @()
foreach ($root in $roots) {
    if (-not (Test-Path $root)) { continue }
    $files = if ((Get-Item $root).PSIsContainer) {
        Get-ChildItem -Path $root -Recurse -File -Include *.java,*.md,*.xml,*.yml,*.yaml,*.properties
    } else {
        @(Get-Item $root)
    }
    foreach ($file in $files) {
        if ($file.FullName -match "SourceQualityScanTest\.java$") { continue }
        $content = (Get-Content $file.FullName -Raw).ToLower()
        foreach ($p in $patterns) {
            if ($content.Contains($p)) {
                $hits += "$($file.FullName) -> $p"
            }
        }
    }
}
if ($hits.Count -gt 0) {
    $hits | ForEach-Object { Write-Host $_ }
    throw "Legacy identifier scan failed"
}

Write-Host "==> [4/4] secret leak scan in git history"
$secretHits = git log -p --all 2>$null | Select-String -Pattern "sk-[a-zA-Z0-9]{20,}|Bearer [a-zA-Z0-9._-]{20,}" -SimpleMatch
if ($secretHits) {
    Write-Host $secretHits
    throw "Possible secret in git history"
}

Write-Host ""
Write-Host "All checks passed."
