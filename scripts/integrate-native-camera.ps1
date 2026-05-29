#Requires -Version 5.1
<#
.SYNOPSIS
  Integrate PhotoAI-Camera native plugin into frontend UniApp project.

.EXAMPLE
  .\scripts\integrate-native-camera.ps1
  .\scripts\integrate-native-camera.ps1 -Rebuild
  .\scripts\integrate-native-camera.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Rebuild,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Frontend = Join-Path $Root 'frontend'
$AndroidPlugin = Join-Path $Root 'android-plugin'
$PluginSrc = Join-Path $AndroidPlugin 'camera-plugin-src'
$LibsDir = Join-Path $AndroidPlugin 'libs'
$UniSdkAar = Join-Path $LibsDir 'uniapp-v8-release.aar'
$PluginTemplate = Join-Path $AndroidPlugin 'nativeplugins\PhotoAI-Camera'
$PluginDest = Join-Path $Frontend 'nativeplugins\PhotoAI-Camera'
$BuiltAar = Join-Path $PluginSrc 'build\outputs\aar\photoai-camera-release.aar'
$DestAar = Join-Path $PluginDest 'android\PhotoAICamera.aar'
$SplashDir = Join-Path $Frontend 'nativeResources\android\splash'

function Write-Step([string]$msg) { Write-Host ""; Write-Host ">> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "   OK: $msg" -ForegroundColor Green }
function Write-WarnMsg([string]$msg) { Write-Host "   WARN: $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "   FAIL: $msg" -ForegroundColor Red }

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " PhotoAI Native Camera - Integrate" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Step "Check paths and dependencies"

if (-not (Test-Path $Frontend)) {
    Write-Fail "frontend not found: $Frontend"
    exit 1
}
if (-not (Test-Path $PluginSrc)) {
    Write-Fail "plugin source not found: $PluginSrc"
    exit 1
}

if (-not (Test-Path $UniSdkAar)) {
    Write-WarnMsg "Missing uni-app SDK: $UniSdkAar"
    Write-Host "Copy uniapp-v8-release.aar from HBuilderX Android SDK to android-plugin/libs/" -ForegroundColor Yellow
    Write-Host "https://nativesupport.dcloud.net.cn/AppDocs/download/android.html" -ForegroundColor Yellow
    if (-not $Yes) {
        $cont = Read-Host "Continue anyway? [y/N]"
        if ($cont -notmatch '^[yY]') { exit 1 }
    }
} else {
    Write-Ok "uniapp-v8-release.aar"
}

if (-not (Test-Path (Join-Path $PluginSrc 'gradlew.bat'))) {
    Write-Fail "gradlew.bat missing in camera-plugin-src"
    exit 1
}

$localProps = Join-Path $PluginSrc 'local.properties'
if (-not (Test-Path $localProps)) {
    $sdkDir = $env:ANDROID_HOME
    if (-not $sdkDir) { $sdkDir = 'D:\Android\sdk' }
    if (Test-Path $sdkDir) {
        $escaped = $sdkDir -replace '\\', '\\'
        Set-Content -Path $localProps -Value "sdk.dir=$escaped" -Encoding UTF8
        Write-Ok "created local.properties -> $sdkDir"
    } else {
        Write-WarnMsg "ANDROID_HOME not set; Gradle may fail"
    }
}

Write-Step "Build AAR"

$needBuild = (-not $SkipBuild) -and ($Rebuild -or -not (Test-Path $BuiltAar))

if ($SkipBuild) {
    Write-Ok "SkipBuild"
} elseif ($needBuild) {
    Push-Location $PluginSrc
    try {
        & .\gradlew.bat assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "Gradle failed with exit code $LASTEXITCODE"
            exit 1
        }
        Write-Ok "assembleRelease"
    } finally {
        Pop-Location
    }
} else {
    Write-Ok "Using existing AAR"
}

if (-not (Test-Path $BuiltAar)) {
    Write-Fail "AAR not found: $BuiltAar"
    exit 1
}

Write-Step "Copy plugin to frontend/nativeplugins"

New-Item -ItemType Directory -Force -Path (Join-Path $PluginDest 'android') | Out-Null

if (-not (Test-Path (Join-Path $PluginTemplate 'package.json'))) {
    Write-Fail "package.json missing in $PluginTemplate"
    exit 1
}

Copy-Item (Join-Path $PluginTemplate 'package.json') (Join-Path $PluginDest 'package.json') -Force
Copy-Item $BuiltAar $DestAar -Force
$aarKb = [math]::Round((Get-Item $DestAar).Length / 1KB, 1)
Write-Ok "PhotoAICamera.aar ($aarKb KB)"

Write-Step "Copy splash images to nativeResources"

New-Item -ItemType Directory -Force -Path $SplashDir | Out-Null

$splashMap = @(
    @{ Pattern = '*480x762*';  Out = 'snappro_ai_hdpi.png' }
    @{ Pattern = '*720x1242*'; Out = 'snappro_ai_xhdpi.png' }
    @{ Pattern = '*1080x1882*'; Out = 'snappro_ai_xxhdpi.png' }
)

$downloadDir = Join-Path $env:USERPROFILE 'Downloads'
$copied = 0

foreach ($item in $splashMap) {
    $dest = Join-Path $SplashDir $item.Out
    $srcFile = $null

    if (Test-Path $downloadDir) {
        $srcFile = Get-ChildItem -Path $downloadDir -Filter $item.Pattern -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }

    if ($srcFile) {
        Copy-Item $srcFile.FullName $dest -Force
        Write-Ok "$($item.Out) <- $($srcFile.Name)"
        $copied++
    } elseif (Test-Path $dest) {
        Write-Ok "keep existing $($item.Out)"
        $copied++
    } else {
        Write-WarnMsg "missing $($item.Pattern); add file: $dest"
    }
}

if ($copied -lt 3) {
    Write-WarnMsg "Incomplete splash images may break cloud packaging"
}

Write-Step "Patch manifest.json"

$patchScript = Join-Path $PSScriptRoot 'patch-app-manifest.mjs'
if (-not (Test-Path $patchScript)) {
    Write-Fail "patch-app-manifest.mjs not found"
    exit 1
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Fail "node not found; install Node.js"
    exit 1
}

& node $patchScript
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Ok "manifest.json"

Write-Step "Verify"

$allOk = $true
$verifyPaths = @(
    $DestAar,
    (Join-Path $PluginDest 'package.json'),
    (Join-Path $Frontend 'src\api\photoai.js')
)

foreach ($p in $verifyPaths) {
    if (Test-Path $p) { Write-Ok $p } else { Write-Fail $p; $allOk = $false }
}

$manifestPath = Join-Path $Frontend 'src\manifest.json'
$manifest = Get-Content $manifestPath -Raw -Encoding UTF8
if ($manifest -match 'PhotoAI-Camera') { Write-Ok 'nativePlugins in manifest' } else { Write-Fail 'nativePlugins'; $allOk = $false }
if ($manifest -match 'nativeResources/android/splash') { Write-Ok 'splash relative paths' } else { Write-Fail 'splash paths'; $allOk = $false }
if ($manifest -match 'C:/Users/') { Write-WarnMsg 'manifest still has C:/ absolute paths' }

if (-not $allOk) {
    Write-Fail "Verification failed"
    exit 1
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Integration complete" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Next in HBuilderX:" -ForegroundColor White
Write-Host "  1. Open folder: frontend" -ForegroundColor White
Write-Host "  2. manifest -> enable PhotoAI-Camera native plugin" -ForegroundColor White
Write-Host "  3. Run -> make custom debug base (Android)" -ForegroundColor White
Write-Host "  4. Run app with custom base on device" -ForegroundColor White
Write-Host ""
Write-Host "Rebuild plugin: .\scripts\integrate-native-camera.ps1 -Rebuild" -ForegroundColor Gray
