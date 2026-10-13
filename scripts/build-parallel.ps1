param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$Versions = @(),
    [ValidateRange(1,2)][int]$MaxParallel = 2
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'This optional parallel build script requires PowerShell 7. Use build-all.ps1 on Windows PowerShell.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$matrix = Get-Content -LiteralPath 'versions.json' -Raw | ConvertFrom-Json
if ($Versions.Count -eq 0) { $Versions = $matrix.PSObject.Properties.Name }
foreach ($minecraft in $Versions) { if (-not ($matrix.PSObject.Properties.Name -contains $minecraft)) { throw "Unknown target: $minecraft" } }
New-Item -ItemType Directory -Force -Path 'dist\logs' | Out-Null
$results = $Versions | ForEach-Object -ThrottleLimit $MaxParallel -Parallel {
    $minecraft = $_
    $root = $using:projectRoot
    Set-Location -LiteralPath $root
    $env:JAVA_HOME = $using:JavaHome
    Write-Host "Building isolated Minecraft $minecraft..."
    $logPath = Join-Path $root "dist\logs\$minecraft.log"
    $cachePath = Join-Path $root "build\project-caches\$minecraft"
    & .\gradlew.bat "-Pmc=$minecraft" '-PisolatedBuild=true' '--project-cache-dir' $cachePath '-Dorg.gradle.jvmargs=-Xmx2G' ':fabric:build' ':neoforge:build' '--console=plain' *> $logPath
    $success = $LASTEXITCODE -eq 0
    if ($success) {
        foreach ($loader in @('fabric','neoforge')) {
            Copy-Item -LiteralPath (Join-Path $root "build\targets\$minecraft\$loader\libs\subby-$loader-$minecraft-1.0.0.jar") -Destination (Join-Path $root 'dist')
        }
        Write-Host "Minecraft $minecraft passed."
    } else { Write-Warning "Minecraft $minecraft failed; see $logPath" }
    [pscustomobject]@{ minecraft = $minecraft; built = $success }
}
ConvertTo-Json -InputObject @($results) | Set-Content -LiteralPath 'dist\build-results.json' -Encoding utf8
if ($results.built -contains $false) { throw 'One or more isolated builds failed. Inspect dist/logs.' }
