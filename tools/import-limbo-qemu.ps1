param(
    [Parameter(Mandatory = $true)]
    [string]$LimboRoot,

    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
    [string[]]$Abi = @("arm64-v8a", "x86_64")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedLimboRoot = (Resolve-Path -LiteralPath $LimboRoot).Path
$licenseSource = Join-Path $resolvedLimboRoot "COPYING"
$requiredDependencies = @(
    "libcompat-limbo.so",
    "libglib-2.0.so",
    "libpixman-1.so"
)

foreach ($targetAbi in $Abi) {
    $dependencyDirectory = Join-Path $resolvedLimboRoot "limbo-android-lib\src\main\jniLibs\$targetAbi"
    $coreDirectory = Join-Path $resolvedLimboRoot "limbo-android-x86\src\main\jniLibs\$targetAbi"
    $coreLibrary = Join-Path $coreDirectory "libqemu-system-x86_64.so"
    if (-not (Test-Path -LiteralPath $coreLibrary -PathType Leaf)) {
        throw "Missing QEMU core for $targetAbi: $coreLibrary"
    }
    if (-not (Test-Path -LiteralPath $dependencyDirectory -PathType Container)) {
        throw "Missing Limbo dependencies for $targetAbi: $dependencyDirectory"
    }
    foreach ($dependency in $requiredDependencies) {
        $dependencyPath = Join-Path $dependencyDirectory $dependency
        if (-not (Test-Path -LiteralPath $dependencyPath -PathType Leaf)) {
            throw "Missing required dependency for ${targetAbi}: $dependencyPath"
        }
    }

    $destination = Join-Path $projectRoot "app\src\main\jniLibs\$targetAbi"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Get-ChildItem -LiteralPath $dependencyDirectory -Filter "*.so" -File |
        Copy-Item -Destination $destination -Force
    Copy-Item -LiteralPath $coreLibrary -Destination $destination -Force
    Write-Host "Imported QEMU core and dependencies for $targetAbi"
}

if (Test-Path -LiteralPath $licenseSource -PathType Leaf) {
    $licenseDestination = Join-Path $projectRoot "app\src\main\assets\licenses"
    New-Item -ItemType Directory -Force -Path $licenseDestination | Out-Null
    Copy-Item -LiteralPath $licenseSource `
        -Destination (Join-Path $licenseDestination "limbo-gpl-2.0.txt") -Force
}

Write-Host "Limbo/QEMU import completed. Build with -PenableQemuBridge=true."
