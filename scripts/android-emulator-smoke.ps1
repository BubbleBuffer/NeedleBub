$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$gradle = Join-Path $repositoryRoot 'android\gradlew.bat'
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
$apk = Join-Path $repositoryRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$applicationId = 'de.x0bubbuff.needlebub'
$listener = "$applicationId/de.x0bubbuff.needlebub.notifications.NeedleNotificationListenerService"

$devices = & $adb devices
$emulatorLine = $devices | Where-Object { $_ -match '^emulator-\d+\s+device$' } | Select-Object -First 1
if (-not $emulatorLine) {
    throw 'A booted Android emulator is required.'
}
$emulatorSerial = ($emulatorLine -split '\s+')[0]
$adbTarget = @('-s', $emulatorSerial)

# The AVD persists its lock credential across smoke runs. Clear the harness-owned
# PIN before instrumentation so Gradle never waits behind the keyguard.
& $adb @adbTarget shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb @adbTarget shell input text 2468 | Out-Null
& $adb @adbTarget shell input keyevent KEYCODE_ENTER | Out-Null
& $adb @adbTarget shell wm dismiss-keyguard | Out-Null
& $adb @adbTarget shell locksettings clear --old 2468 2>$null | Out-Null

function Get-WebViewTarget {
    $match = $null
    for ($attempt = 0; $attempt -lt 30; $attempt += 1) {
        $socketOutput = & $adb @adbTarget shell cat /proc/net/unix
        $candidate = [regex]::Match(($socketOutput -join "`n"), '@(webview_devtools_remote_\d+)')
        if ($candidate.Success) { $match = $candidate; break }
        Start-Sleep -Milliseconds 250
    }
    if ($null -eq $match) { throw 'NeedleBub WebView debugging socket was not found.' }
    $forward = (& $adb @adbTarget forward --list) | Where-Object { $_ -match "^$([regex]::Escape($emulatorSerial))\s+tcp:9222\s" }
    if ($forward) { & $adb @adbTarget forward --remove tcp:9222 | Out-Null }
    & $adb @adbTarget forward tcp:9222 "localabstract:$($match.Groups[1].Value)" | Out-Null
    return (Invoke-RestMethod 'http://127.0.0.1:9222/json')[0].webSocketDebuggerUrl
}

function Invoke-CdpExpression([string]$Target, [string]$Expression) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Expression))
    $node = "const x=Buffer.from(process.argv[2],'base64').toString();const w=new WebSocket(process.argv[1]);w.onopen=()=>w.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:x,returnByValue:true,awaitPromise:true}}));w.onmessage=e=>{const m=JSON.parse(e.data);if(m.id===1){if(m.result.exceptionDetails){console.error(JSON.stringify(m.result.exceptionDetails));process.exitCode=1}else if(m.result.result.value!==undefined){process.stdout.write(String(m.result.result.value))}w.close()}}"
    $result = & node -e $node $Target $encoded
    if ($LASTEXITCODE -ne 0) { throw 'Chrome DevTools evaluation failed.' }
    return $result
}

Push-Location $repositoryRoot
try {
    & $npmCommand run android:prepare
    if ($LASTEXITCODE -ne 0) { throw 'Android web assets could not be prepared.' }
} finally {
    Pop-Location
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

& $adb @adbTarget uninstall $applicationId 2>$null | Out-Null
& $adb @adbTarget install $apk
if ($LASTEXITCODE -ne 0) { throw 'Emulator APK installation failed.' }
# A prior interrupted smoke run may have left the deterministic test PIN behind.
# Clearing it first keeps the harness repeatable; failure is harmless on a fresh AVD.
& $adb @adbTarget shell locksettings clear --old 2468 2>$null | Out-Null
& $adb @adbTarget shell locksettings set-pin 2468 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not configure the emulator device credential.' }
# Setting a credential can repeatedly surface Google setup UI on Play-enabled
# AVDs. Disable Play Services for this local smoke; device authentication itself
# is provided by Android's lock screen and remains available.
& $adb @adbTarget shell pm disable-user --user 0 com.google.android.gms | Out-Null
& $adb @adbTarget shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb @adbTarget shell wm dismiss-keyguard | Out-Null
& $adb @adbTarget shell pm grant $applicationId android.permission.POST_NOTIFICATIONS
& $adb @adbTarget shell cmd notification allow_listener $listener
& $adb @adbTarget shell am start -n "$applicationId/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

$target = Get-WebViewTarget
$enableCapture = @'
(async()=>{
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const find=async(selector,predicate,label)=>{
    for(let attempt=0;attempt<40;attempt+=1){
      const element=[...document.querySelectorAll(selector)].find(predicate);
      if(element) return element;
      await wait(250);
    }
    throw new Error(`${label} was not rendered`);
  };
  const settings=await find('button',button=>button.getAttribute('aria-label')==='Settings','Settings button');
  settings.click();
  await wait(150);
  const buildFacts=await find('summary',element=>element.innerText.includes('Diagnostics and build facts'),'Build facts');
  buildFacts.click();
  await wait(150);
  const version=await find('.diagnostic-unlock-target',()=>true,'Version entry');
  for(let index=0;index<7;index+=1) version.click();
  await wait(600);
  const lab=await find('button',button=>button.innerText.includes('Notification Lab'),'Notification Lab');
  lab.click();
  return location.hash;
})()
'@
Invoke-CdpExpression $target $enableCapture | Out-Null
Start-Sleep -Seconds 1
& $adb @adbTarget shell input text 2468
& $adb @adbTarget shell input keyevent 66
Start-Sleep -Seconds 2

$target = Get-WebViewTarget
$enableAuthenticatedCapture = @'
(async()=>{
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const find=async(selector,predicate,label)=>{
    for(let attempt=0;attempt<40;attempt+=1){
      const element=[...document.querySelectorAll(selector)].find(predicate);
      if(element) return element;
      await wait(250);
    }
    throw new Error(`${label} was not rendered`);
  };
  window.confirm=()=>true;
  const capture=await find('input[aria-label="Notification capture"]',()=>true,'Developer capture switch');
  if(!capture.checked) capture.click();
  for(let attempt=0;attempt<20;attempt+=1){
    await wait(200);
    if(capture.checked) break;
  }
  return document.body.innerText;
})()
'@
$enabledView = Invoke-CdpExpression $target $enableAuthenticatedCapture
$captureEnabled = $false
for ($attempt = 0; $attempt -lt 20; $attempt += 1) {
    $preferences = (& $adb @adbTarget shell run-as $applicationId cat shared_prefs/developer_data.xml) -join "`n"
    if ($preferences -match 'name="capture_enabled" value="true"') { $captureEnabled = $true; break }
    Start-Sleep -Milliseconds 250
}
if (-not $captureEnabled) { throw "Developer notification capture did not become enabled.`n$enabledView" }

& $adb @adbTarget logcat -c
& $adb @adbTarget shell "cmd notification post -t 'Avoiding Bot Detection' needlebub-emulator-smoke 'User0332'" | Out-Null
Start-Sleep -Seconds 2
$crashLog = (& $adb @adbTarget logcat -d -v brief AndroidRuntime:E '*:S') -join "`n"
if ($crashLog -match 'FATAL EXCEPTION') { throw "NeedleBub crashed after notification capture.`n$crashLog" }
if (-not (& $adb @adbTarget shell pidof $applicationId)) { throw 'NeedleBub process exited after notification capture.' }

$target = Get-WebViewTarget
$refreshRecords = @'
(async()=>{
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const all=[...document.querySelectorAll('.lab-filters button')].find(button=>button.innerText==='All');
  if(!all) throw new Error('Lab filter was not rendered');
  all.click();
  await wait(750);
  const newest=document.querySelector('.record-row');
  if(!newest) throw new Error('Captured notification row was not rendered');
  newest.click();
  await wait(500);
  return document.body.innerText;
})()
'@
$view = (Invoke-CdpExpression $target $refreshRecords) -join "`n"
if ($view -notmatch 'com\.android\.shell' -or $view -notmatch 'SOURCE_NOT_SELECTED' -or $view -notmatch 'Not run') {
    throw "Notification listener did not persist the expected visible Lab record.`n$view"
}

& $adb @adbTarget shell pm enable com.google.android.gms | Out-Null
Write-Output 'NeedleBub emulator smoke passed: Keystore encryption, authenticated Lab, real notification listener, process survival, and visible capture record.'
