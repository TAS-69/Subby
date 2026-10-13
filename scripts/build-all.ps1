param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string[]]$Versions = @(),
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 25. Install JDK 21 as a Gradle toolchain as well.' }
$matrix = Get-Content -LiteralPath (Join-Path $projectRoot 'versions.json') -Raw | ConvertFrom-Json
if ($Versions.Count -eq 0) { $Versions = $matrix.PSObject.Properties.Name }
$releaseVersion = ((Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^version=' }) -replace '^version=','').Trim()
$destination = Join-Path $projectRoot "dist\$releaseVersion"
New-Item -ItemType Directory -Force -Path $destination | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $destination 'logs') | Out-Null
if (-not $SkipTests) {
    & .\gradlew.bat '-Pplatform=paper' ':core:test' ':paper:build' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Core tests or plugin build failed.' }
    Get-ChildItem -LiteralPath 'paper\build\libs' -Filter "*-$releaseVersion.jar" | Where-Object { $_.Name -notlike '*-sources.jar' } | Copy-Item -Destination $destination
}
$results = @()
foreach ($minecraft in $Versions) {
    if (-not ($matrix.PSObject.Properties.Name -contains $minecraft)) { throw "Unknown target: $minecraft" }
    Write-Host "Building Subby for Minecraft $minecraft (Fabric and NeoForge)..."
    $logPath = Join-Path $destination "logs\$minecraft.log"
    & .\gradlew.bat "-Pmc=$minecraft" ':fabric:build' ':neoforge:build' '--console=plain' *> $logPath
    $success = $LASTEXITCODE -eq 0
    $results += [pscustomobject]@{ minecraft = $minecraft; built = $success }
    if ($success) {
        foreach ($loader in @('fabric','neoforge')) {
            $jarPath = Join-Path $projectRoot "$loader\build\libs\subby-$loader-$minecraft-$releaseVersion.jar"
            Copy-Item -LiteralPath $jarPath -Destination $destination
        }
    } else { Write-Warning "Build failed for $minecraft; see $logPath" }
    ConvertTo-Json -InputObject @($results) | Set-Content -LiteralPath (Join-Path $destination 'build-results.json') -Encoding utf8
}
Get-ChildItem -LiteralPath $destination -Filter '*.jar' | Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path -Leaf $_.Path)" } | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding ascii
if ($results.built -contains $false) { throw 'One or more Minecraft versions failed. Inspect dist/logs.' }
Write-Host "Finished. Installable JARs are in $destination"
