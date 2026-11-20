# Sends one debug command to the debug app and prints its TFSTATE line (docs/plan/02-playback-engine.md T8).
#   scripts\device.ps1 -Device moto -Cmd play_path -Extra @('--es','path','/storage/emulated/0/TrueformTest')
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$Cmd,
    [string[]]$Extra = @(),
    [int]$WaitMs = 600
)
. "$PSScriptRoot\env.ps1"
$serial = Resolve-TfSerial $Device
# adb shell joins its arguments with spaces for sh: quote every value that has one
$quoted = $Extra | ForEach-Object { if ($_ -match '\s') { "'" + ($_ -replace "'", "'\''") + "'" } else { $_ } }
# -f 32: FLAG_INCLUDE_STOPPED_PACKAGES, so a command also reaches the app after a test run left it stopped
$args2 = @('shell', 'am', 'broadcast', '-f', '32', '-n', "$TF_APP_ID/dev.trueform.player.DebugReceiver", '-a', 'tf.CMD', '--es', 'cmd', $Cmd) + $quoted
& $TF_ADB -s $serial @args2 | Out-Null
Start-Sleep -Milliseconds $WaitMs
$p = Get-TfPid $serial
if ($p) { & $TF_ADB -s $serial logcat -d --pid=$p -s TFSTATE:I TFDEBUG:* | Select-Object -Last 1 }
