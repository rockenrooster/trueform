# Installs the built APK on a test phone and starts it.   scripts\deploy.ps1 -Device moto [-Config debug] [-NoStart]
param(
    [Parameter(Mandatory)][string]$Device,
    [ValidateSet('debug', 'release')][string]$Config = 'debug',
    [switch]$NoStart
)
. "$PSScriptRoot\env.ps1"
$serial = Resolve-TfSerial $Device
$apk = Get-ChildItem (Join-Path $TF_ROOT "app\build\outputs\apk\$Config\*.apk") | Select-Object -First 1
if (-not $apk) { throw "no $Config APK: run scripts\build.ps1 -Config $Config" }
$pkg = if ($Config -eq 'debug') { $TF_APP_ID } else { 'dev.trueform.player' }
Enter-TfDeviceLock $serial
try {
    & $TF_ADB -s $serial install -r -t $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw "install failed" }
    if (-not $NoStart) {
        Wake-TfDevice $serial
        if (Test-TfDeviceFree $serial) {
            & $TF_ADB -s $serial shell am start -W -n "$pkg/dev.trueform.player.MainActivity" | Out-Null
            Write-Host "[deploy] started $pkg on $Device ($serial), pid $(Get-TfPid $serial $pkg)"
        } else {
            Write-Host "[deploy] installed; not started: $(Get-TfForegroundPackage $serial) is in front"
        }
    }
} finally { Exit-TfDeviceLock $serial }
