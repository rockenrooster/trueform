# JVM unit tests, or the device tests on one phone.   scripts\test.ps1 [-Device moto] [-Filter <class or method>]
param([string]$Device = '', [string]$Filter = '')
. "$PSScriptRoot\env.ps1"
Push-Location $TF_ROOT
try {
    if (-not $Device) {
        $args2 = @('--max-workers=4', '--console=plain', ':app:testDebugUnitTest')
        if ($Filter) { $args2 += @('--tests', $Filter) }
        & .\gradlew.bat @args2
        if ($LASTEXITCODE -ne 0) { throw "unit tests failed" }
        return
    }
    $serial = Resolve-TfSerial $Device
    Enter-TfDeviceLock $serial
    try {
        $env:ANDROID_SERIAL = $serial
        # Keep the app installed afterwards: AGP uninstalls it by default, which wipes the phone's library and settings
        $args2 = @('--max-workers=4', '--console=plain', ':app:connectedDebugAndroidTest',
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')
        if ($Filter) { $args2 += "-Pandroid.testInstrumentationRunnerArguments.class=$Filter" }
        & .\gradlew.bat @args2
        if ($LASTEXITCODE -ne 0) { throw "device tests failed" }
    } finally {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
        Exit-TfDeviceLock $serial
    }
} finally { Pop-Location }
