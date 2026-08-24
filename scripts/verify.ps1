$ErrorActionPreference = 'Stop'
$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

Push-Location $repositoryRoot
try {
    & npm.cmd test -- --maxWorkers=1
    if ($LASTEXITCODE -ne 0) { throw 'Web tests failed.' }
    & npm.cmd run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'Type checking failed.' }
    & npm.cmd run lint
    if ($LASTEXITCODE -ne 0) { throw 'Lint failed.' }
    & npm.cmd audit --audit-level=high
    if ($LASTEXITCODE -ne 0) { throw 'Dependency audit failed.' }
    & npm.cmd run pack:otp
    if ($LASTEXITCODE -ne 0) { throw 'OTP pack build failed.' }
    & npm.cmd run android:prepare
    if ($LASTEXITCODE -ne 0) { throw 'Android preparation failed.' }

    Push-Location android
    try {
        & .\gradlew.bat testDebugUnitTest assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android verification failed.' }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $repositoryRoot 'android\app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Release APK is missing.' }
    $jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    $entries = & $jar tf $apk
    $forbiddenEntries = $entries | Where-Object { $_ -match '\.(?:cact|pkl|parquet|jsonl|csv|safetensors|ckpt)$' }
    if ($forbiddenEntries) { throw "Forbidden private artifact in APK: $($forbiddenEntries -join ', ')" }

    $aapt = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\aapt.exe'
    $badging = (& $aapt dump badging $apk) -join "`n"
    if ($badging -notmatch "package: name='de.x0bubbuff.needlebub'") { throw 'APK package name is wrong.' }
    if ($badging -notmatch "sdkVersion:'31'") { throw 'APK minSdk is wrong.' }
    if ($badging -notmatch "targetSdkVersion:'36'") { throw 'APK targetSdk is wrong.' }
    if ($badging -notmatch "native-code: 'arm64-v8a'") { throw 'APK is not ARM64-only.' }

    $artifactDirectory = Join-Path $repositoryRoot 'artifacts'
    New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null
    Copy-Item -LiteralPath $apk -Destination (Join-Path $artifactDirectory 'NeedleBub-0.1.0-alpha.1-arm64.apk') -Force
    Write-Output 'NeedleBub verification completed.'
} finally {
    Pop-Location
}
