param([string]$Version = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $Version) { $Version = ((Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^version=' }) -replace '^version=','').Trim() }
$releaseDirectory = Join-Path $projectRoot "dist\$Version"
if (-not (Test-Path -LiteralPath $releaseDirectory)) { $releaseDirectory = Join-Path $projectRoot 'dist' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stage = Join-Path $projectRoot ('build\packages\' + [guid]::NewGuid().ToString())
$downloadStage = Join-Path $stage 'downloads'
$sourceStage = Join-Path $stage 'source'
New-Item -ItemType Directory -Force -Path $downloadStage,$sourceStage | Out-Null
$jars = Get-ChildItem -LiteralPath $releaseDirectory -Filter "*-$Version.jar"
if ($jars.Count -ne 33) { throw "Expected 33 installable JARs, found $($jars.Count). Run the full build matrix first." }
$jars | Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path -Leaf $_.Path)" } | Set-Content -LiteralPath (Join-Path $releaseDirectory 'SHA256SUMS.txt') -Encoding ascii
$jars | Copy-Item -Destination $downloadStage
$rootFiles = @('README.md','CHANGELOG.md','LICENSE','Subby-logo.png','Subby-logo.svg','versions.json')
foreach ($file in $rootFiles) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $downloadStage
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $sourceStage
}
Copy-Item -LiteralPath (Join-Path $releaseDirectory 'SHA256SUMS.txt'),(Join-Path $releaseDirectory 'build-results.json') -Destination $downloadStage
New-Item -ItemType Directory -Force -Path (Join-Path $downloadStage 'gradle') | Out-Null
Copy-Item -LiteralPath 'gradle\NOTICE','gradle\LICENSE' -Destination (Join-Path $downloadStage 'gradle')
foreach ($file in @('build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','.gitignore','.gitattributes')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $sourceStage
}
foreach ($directory in @('gradle','minecraft','integration','scripts')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $directory) -Destination $sourceStage -Recurse
}
foreach ($module in @('core','fabric','neoforge','paper')) {
    $moduleStage = Join-Path $sourceStage $module
    New-Item -ItemType Directory -Force -Path $moduleStage | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "$module\src") -Destination $moduleStage -Recurse
    Copy-Item -LiteralPath (Join-Path $projectRoot "$module\build.gradle") -Destination $moduleStage
}
foreach ($kind in @('downloads','source')) {
    $zipPath = Join-Path $releaseDirectory "Subby-$Version-$kind.zip"
    if (Test-Path -LiteralPath $zipPath) { throw "Package already exists: $zipPath. Choose a new version or remove that exact ZIP first." }
    [System.IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $stage $kind), $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "Created $zipPath"
}
