$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

Push-Location (Join-Path $PSScriptRoot '..\android')
try {
    & .\gradlew.bat testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android unit tests failed.' }
} finally {
    Pop-Location
}
