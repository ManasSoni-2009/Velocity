param(
    [switch]$SkipRust,
    [switch]$SkipNdk,
    [switch]$SkipGradleTests,
    [switch]$Deploy
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ToolsDir = Join-Path $RepoRoot ".tools"

New-Item -ItemType Directory -Force -Path `
    (Join-Path $ToolsDir "tmp"), `
    (Join-Path $ToolsDir "bin"), `
    (Join-Path $ToolsDir "cargo"), `
    (Join-Path $ToolsDir "rustup"), `
    (Join-Path $ToolsDir "gradle"), `
    (Join-Path $ToolsDir "android-sdk") | Out-Null

# Keep all tool caches, temp files, and NDK strictly inside D:\Projects\Velocity\.tools
$env:TEMP = Join-Path $ToolsDir "tmp"
$env:TMP = Join-Path $ToolsDir "tmp"
$env:RUSTUP_HOME = Join-Path $ToolsDir "rustup"
$env:CARGO_HOME = Join-Path $ToolsDir "cargo"
$env:GRADLE_USER_HOME = Join-Path $ToolsDir "gradle"
$env:ANDROID_NDK_HOME = Join-Path $ToolsDir "android-sdk\ndk\27.2.12479018"

if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot")) {
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
}
if (-not $env:ANDROID_HOME -and (Test-Path "C:\Android\Sdk")) {
    $env:ANDROID_HOME = "C:\Android\Sdk"
}

$ToolsBin = Join-Path $ToolsDir "bin"
$CargoBin = Join-Path $ToolsDir "cargo\bin"
$NdkLlvmBin = Join-Path $env:ANDROID_NDK_HOME "toolchains\llvm\prebuilt\windows-x86_64\bin"
if ((Test-Path "$NdkLlvmBin\llvm-dlltool.exe") -and -not (Test-Path "$ToolsBin\llvm-dlltool.exe")) {
    Copy-Item "$NdkLlvmBin\llvm-dlltool.exe" "$ToolsBin\llvm-dlltool.exe" -Force
    Copy-Item "$NdkLlvmBin\llvm-dlltool.exe" "$ToolsBin\x86_64-w64-mingw32-dlltool.exe" -Force
}

$WindhawkBin = "C:\Program Files\Windhawk\Compiler\bin"
$WindhawkShort = $null
if (Test-Path $WindhawkBin) {
    $WindhawkShort = (New-Object -ComObject Scripting.FileSystemObject).GetFolder($WindhawkBin).ShortPath
}
$CleanPath = ($env:PATH -split ';' | Where-Object { $_ -and $_ -notmatch 'MinGW' }) -join ';'
if ($WindhawkShort) {
    $env:PATH = "$ToolsBin;$CargoBin;$WindhawkShort;$CleanPath"
    $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_LINKER = "$WindhawkShort\clang-20.exe"
    $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNULLVM_RUSTFLAGS = "-C link-arg=--config -C link-arg=$WindhawkShort\x86_64-w64-windows-gnu.cfg"
} else {
    $env:PATH = "$ToolsBin;$CargoBin;$CleanPath"
}
Remove-Item Env:\RUSTFLAGS -ErrorAction SilentlyContinue

Push-Location $RepoRoot
try {
    if (-not $SkipRust) {
        Write-Host "`n=== [1/5] Running Rust Native & Relay Tests ===" -ForegroundColor Cyan
        & cargo +stable-x86_64-pc-windows-gnullvm test --manifest-path native/Cargo.toml --all-targets
        if ($LASTEXITCODE -ne 0) { throw "Native Rust tests failed" }

        & cargo +stable-x86_64-pc-windows-gnullvm test --manifest-path server/Cargo.toml --all-targets
        if ($LASTEXITCODE -ne 0) { throw "Server Rust tests failed" }
    }

    if (-not $SkipNdk) {
        Write-Host "`n=== [2/5] Building Android JNI (.so) via cargo-ndk ===" -ForegroundColor Cyan
        if (-not (Test-Path $env:ANDROID_NDK_HOME)) {
            throw "Android NDK not found at $env:ANDROID_NDK_HOME"
        }
        $JniOut = Join-Path $RepoRoot "composeApp\src\androidMain\jniLibs"
        New-Item -ItemType Directory -Force -Path $JniOut | Out-Null

        Push-Location (Join-Path $RepoRoot "native")
        try {
            & cargo +stable-x86_64-pc-windows-gnullvm ndk `
                -t arm64-v8a -t armeabi-v7a -t x86_64 `
                -o $JniOut `
                build --release -p velocity-jni
            if ($LASTEXITCODE -ne 0) { throw "cargo-ndk build failed" }
        } finally {
            Pop-Location
        }
    }

    if (-not $SkipGradleTests) {
        Write-Host "`n=== [3/5] Running Android & Desktop KMP Tests + Screenshots ===" -ForegroundColor Cyan
        & .\gradlew.bat :composeApp:testDebugUnitTest :composeApp:desktopTest --no-daemon --stacktrace
        if ($LASTEXITCODE -ne 0) { throw "Gradle unit/render tests failed" }

        $ComposeShots = Join-Path $RepoRoot "composeApp\docs\screenshots"
        $RootShots = Join-Path $RepoRoot "docs\screenshots"
        if (Test-Path $ComposeShots) {
            New-Item -ItemType Directory -Force -Path $RootShots | Out-Null
            Remove-Item "$RootShots\*.png" -Force -ErrorAction SilentlyContinue
            Copy-Item "$ComposeShots\*.png" $RootShots -Force
        }
    }

    Write-Host "`n=== [4/5] Assembling Android Debug APK ===" -ForegroundColor Cyan
    & .\gradlew.bat :composeApp:assembleDebug --no-daemon --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed" }

    $ApkPath = Join-Path $RepoRoot "composeApp\build\outputs\apk\debug\composeApp-debug.apk"
    if (Test-Path $ApkPath) {
        $SizeMb = [math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
        Write-Host "APK built: $ApkPath ($SizeMb MB)" -ForegroundColor Green
    }

    if ($Deploy) {
        Write-Host "`n=== [5/5] Deploying to Connected Android Device ===" -ForegroundColor Cyan
        $Adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
        if (-not (Test-Path $Adb)) { $Adb = "adb" }
        $DevicesRaw = & $Adb devices
        $Online = $DevicesRaw | Select-String "\tdevice$"
        if (-not $Online) {
            throw "No connected Android device found via 'adb devices'. Connect phone with USB Debugging enabled and retry."
        }

        Write-Host "Installing APK..."
        & $Adb install -r $ApkPath
        if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

        & $Adb logcat -c
        & $Adb shell am start -n "com.velocity.app/.MainActivity"
        Start-Sleep -Seconds 3

        Write-Host "Recent app logs (VelocityJni / VelocityVpnService / AndroidNetworkMonitor):" -ForegroundColor Yellow
        & $Adb logcat -d -s "VelocityJni:*" "VelocityVpnService:*" "MultiNetworkManager:*" "AndroidNetworkMonitor:*" "AndroidRuntime:E"
    }
} finally {
    Pop-Location
}
