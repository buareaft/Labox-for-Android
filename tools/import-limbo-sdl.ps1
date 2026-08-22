param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [ValidateSet("arm64-v8a", "x86_64")]
    [string[]]$Abi = @("arm64-v8a", "x86_64")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedSource = (Resolve-Path -LiteralPath $SourceRoot).Path
$tag = "v6.0.1-LimboEmulator"
$requiredLibraries = @(
    "libcompat-limbo.so",
    "libcompat-musl.so",
    "libcompat-SDL2-addons.so",
    "libcompat-SDL2-ext.so",
    "libglib-2.0.so",
    "liblimbo.so",
    "libpixman-1.so",
    "libSDL2.so",
    "libqemu-system-x86_64.so"
)

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("labox-limbo-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    Push-Location $tempRoot
    try {
        & tar -xf $resolvedApk
        if ($LASTEXITCODE -ne 0) { throw "无法解压 Limbo APK: $resolvedApk" }
    } finally {
        Pop-Location
    }

    foreach ($targetAbi in $Abi) {
        $sourceDirectory = Join-Path $tempRoot "lib\$targetAbi"
        foreach ($library in $requiredLibraries) {
            $sourcePath = Join-Path $sourceDirectory $library
            if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
                throw "Limbo APK 缺少 ${targetAbi}/${library}"
            }
        }

        $destination = Join-Path $projectRoot "app\src\main\jniLibs\$targetAbi"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        foreach ($library in $requiredLibraries) {
            $destinationName = if ($library -eq "libqemu-system-x86_64.so") {
                "libqemu-system-x86_64-sdl.so"
            } else {
                $library
            }
            Copy-Item -LiteralPath (Join-Path $sourceDirectory $library) `
                -Destination (Join-Path $destination $destinationName) -Force
        }
        Write-Host "已导入 Limbo SDL 原生库: $targetAbi"
    }

    $sdlSource = "limbo-android-lib/src/main/java/org/libsdl/app"
    $sdlDestination = Join-Path $projectRoot "app\src\main\java\org\libsdl\app"
    New-Item -ItemType Directory -Force -Path $sdlDestination | Out-Null
    foreach ($javaFile in @("SDL.java", "SDLActivity.java", "SDLAudioManager.java", "SDLControllerManager.java")) {
        $content = & git -C $resolvedSource show "${tag}:${sdlSource}/${javaFile}"
        if ($LASTEXITCODE -ne 0) { throw "无法从 $tag 读取 $javaFile" }
        [System.IO.File]::WriteAllLines((Join-Path $sdlDestination $javaFile), $content)
    }

    $licenseDestination = Join-Path $projectRoot "app\src\main\assets\licenses"
    New-Item -ItemType Directory -Force -Path $licenseDestination | Out-Null
    $license = & git -C $resolvedSource show "${tag}:COPYING"
    if ($LASTEXITCODE -ne 0) { throw "无法从 $tag 读取 COPYING" }
    [System.IO.File]::WriteAllLines((Join-Path $licenseDestination "limbo-gpl-2.0.txt"), $license)
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}

Write-Host "Limbo 6.0.1 SDL 导入完成。"
