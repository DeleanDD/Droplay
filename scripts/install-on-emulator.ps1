param(
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [string]$Package = "com.droplay.tv.debug"
)

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb -and $env:ANDROID_HOME) {
    $candidate = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    if (Test-Path $candidate) { $adb = Get-Item $candidate }
}

if (-not $adb) {
    throw "ADB não encontrado. Abra o Android Studio e instale Android SDK Platform-Tools."
}
$adbPath = if ($adb.Source) { $adb.Source } else { $adb.FullName }
if (-not (Test-Path $Apk)) {
    throw "APK não encontrado em $Apk. Gere-o com .\gradlew.bat assembleDebug."
}

& $adbPath wait-for-device
& $adbPath install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "Falha ao instalar o APK no emulador." }
& $adbPath shell am force-stop $Package
& $adbPath shell monkey -p $Package -c android.intent.category.LEANBACK_LAUNCHER 1
