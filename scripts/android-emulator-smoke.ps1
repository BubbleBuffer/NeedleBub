$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$gradle = Join-Path $repositoryRoot 'android\gradlew.bat'
$apk = Join-Path $repositoryRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$applicationId = 'de.x0bubbuff.needlebub'
$listener = "$applicationId/de.x0bubbuff.needlebub.notifications.NeedleNotificationListenerService"

$devices = & $adb devices
if (-not ($devices -match '^emulator-\d+\s+device$')) {
    throw 'A booted Android emulator is required.'
}

function Get-WebViewTarget {
    $socketOutput = & $adb shell cat /proc/net/unix
    $match = [regex]::Match(($socketOutput -join "`n"), '@(webview_devtools_remote_\d+)')
    if (-not $match.Success) { throw 'NeedleBub WebView debugging socket was not found.' }
    & $adb forward --remove tcp:9222 2>$null | Out-Null
    & $adb forward tcp:9222 "localabstract:$($match.Groups[1].Value)" | Out-Null
    return (Invoke-RestMethod 'http://127.0.0.1:9222/json')[0].webSocketDebuggerUrl
}

function Invoke-CdpExpression([string]$Target, [string]$Expression) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Expression))
    $node = "const x=Buffer.from(process.argv[2],'base64').toString();const w=new WebSocket(process.argv[1]);w.onopen=()=>w.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:x,returnByValue:true,awaitPromise:true}}));w.onmessage=e=>{const m=JSON.parse(e.data);if(m.id===1){if(m.result.exceptionDetails){console.error(JSON.stringify(m.result.exceptionDetails));process.exitCode=1}else if(m.result.result.value!==undefined){process.stdout.write(String(m.result.result.value))}w.close()}}"
    $result = & node -e $node $Target $encoded
    if ($LASTEXITCODE -ne 0) { throw 'Chrome DevTools evaluation failed.' }
    return $result
}

Push-Location (Join-Path $repositoryRoot 'android')
try {
    & $gradle :app:connectedDebugAndroidTest -PneedlebubEmulatorRuntimeStub=true '-Pandroid.testInstrumentationRunnerArguments.class=de.x0bubbuff.needlebub.developer.DeveloperDataStoreInstrumentedTest' --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android Keystore instrumentation test failed.' }
    & $gradle :app:assembleDebug -PneedlebubEmulatorRuntimeStub=true --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'x86_64 emulator APK build failed.' }
} finally {
    Pop-Location
}

& $adb uninstall $applicationId 2>$null | Out-Null
& $adb install $apk
if ($LASTEXITCODE -ne 0) { throw 'Emulator APK installation failed.' }
& $adb shell pm grant $applicationId android.permission.POST_NOTIFICATIONS
& $adb shell cmd notification allow_listener $listener
& $adb shell am start -n "$applicationId/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

$target = Get-WebViewTarget
$enableCapture = @'
(async()=>{
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  [...document.querySelectorAll('button')].find(button=>button.getAttribute('aria-label')==='Settings').click();
  await wait(150);
  document.querySelectorAll('summary')[0].click();
  await wait(150);
  const version=document.querySelector('.diagnostic-unlock-target');
  for(let index=0;index<7;index+=1) version.click();
  await wait(600);
  window.confirm=()=>true;
  document.querySelector('.developer-data input').click();
  for(let attempt=0;attempt<20;attempt+=1){
    await wait(200);
    if(document.body.innerText.includes('Capture is on')) break;
  }
  return document.body.innerText;
})()
'@
$enabledView = Invoke-CdpExpression $target $enableCapture
$captureEnabled = $false
for ($attempt = 0; $attempt -lt 20; $attempt += 1) {
    $preferences = (& $adb shell run-as $applicationId cat shared_prefs/developer_data.xml) -join "`n"
    if ($preferences -match 'name="capture_enabled" value="true"') { $captureEnabled = $true; break }
    Start-Sleep -Milliseconds 250
}
if (-not $captureEnabled) { throw "Developer notification capture did not become enabled.`n$enabledView" }

& $adb logcat -c
& $adb shell cmd notification post -t 'Avoiding Bot Detection' needlebub-emulator-smoke 'User0332' | Out-Null
Start-Sleep -Seconds 2
$crashLog = (& $adb logcat -d -v brief AndroidRuntime:E '*:S') -join "`n"
if ($crashLog -match 'FATAL EXCEPTION') { throw "NeedleBub crashed after notification capture.`n$crashLog" }
if (-not (& $adb shell pidof $applicationId)) { throw 'NeedleBub process exited after notification capture.' }

$target = Get-WebViewTarget
Invoke-CdpExpression $target "location.reload(); 'reloading'" | Out-Null
Start-Sleep -Seconds 2
$target = Get-WebViewTarget
$view = (Invoke-CdpExpression $target 'document.body.innerText') -join "`n"
if ($view -notmatch 'Records\s+[1-9]\d*') { throw "Notification listener did not persist a capture.`n$view" }

Write-Output 'NeedleBub emulator smoke passed: Keystore encryption, real notification listener, process survival, and nonzero capture count.'
