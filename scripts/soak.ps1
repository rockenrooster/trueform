# A long local playback run on one phone (docs/plan/11-testing-hardening.md section 3), with the debug build:
#   scripts\soak.ps1 -Device moto -Minutes 480 [-Path /storage/emulated/0/TrueformTest]
# Muted in the app, repeat all. Every 60 s: the player state goes to captures\soak-<device>-<time>.jsonl. At the end:
# minutes played, track changes, underruns, process restarts, the longest time the position did not move.
param(
    [Parameter(Mandatory)][string]$Device,
    [int]$Minutes = 60,
    [string]$Path = '/storage/emulated/0/TrueformTest'
)
. "$PSScriptRoot\env.ps1"
$serial = Resolve-TfSerial $Device
$dev = "$PSScriptRoot\device.ps1"
New-Item -ItemType Directory -Force $TF_CAPTURES | Out-Null
$out = Join-Path $TF_CAPTURES ("soak-$Device-" + (Get-Date -Format 'yyyyMMdd-HHmm') + '.jsonl')
function Get-State {
    $l = & $dev -Device $Device -Cmd dump -WaitMs 700
    if ("$l" -match 'TFSTATE\s*:\s*(\{.*\})') { $Matches[1] } else { $null }
}
Enter-TfDeviceLock $serial
try {
    & $dev -Device $Device -Cmd mute -Extra @('--ez', 'on', 'true') -WaitMs 2000 | Out-Null
    & $dev -Device $Device -Cmd repeat -Extra @('--es', 'v', 'all') -WaitMs 500 | Out-Null
    & $dev -Device $Device -Cmd play_path -Extra @('--es', 'path', $Path) -WaitMs 3000 | Out-Null
    $pid0 = Get-TfPid $serial
    $end = (Get-Date).AddMinutes($Minutes)
    $samples = @()
    while ((Get-Date) -lt $end) {
        Start-Sleep -Seconds 60
        $json = Get-State
        $now = Get-Date -Format 'o'
        if (-not $json) { Add-Content $out "{`"time`":`"$now`",`"error`":`"no state`"}"; continue }
        Add-Content $out ($json.TrimEnd('}') + ",`"time`":`"$now`",`"pid`":`"$(Get-TfPid $serial)`"}")
        $samples += ($json | ConvertFrom-Json)
        if ($samples[-1].state -eq 'IDLE' -and -not $samples[-1].playWhenReady) { Write-Host "[soak] stopped playing: $json"; break }
    }
    $p = Get-TfPid $serial
    $log = & $TF_ADB -s $serial logcat -d --pid=$p -s TFAUDIO:* TFQUEUE:W TFSVC:* AndroidRuntime:E
    $underrunLines = ($log | Select-String 'underrun').Count
    $entries = ($samples | ForEach-Object { $_.entry } | Select-Object -Unique).Count
    $maxStill = 0; $still = 0
    for ($i = 1; $i -lt $samples.Count; $i++) {
        if ($samples[$i].entry -eq $samples[$i - 1].entry -and $samples[$i].position -le $samples[$i - 1].position) { $still++ } else { $still = 0 }
        if ($still -gt $maxStill) { $maxStill = $still }
    }
    $pids = (Get-Content $out | ForEach-Object { if ($_ -match '"pid":"(\d*)"') { $Matches[1] } } | Select-Object -Unique).Count
    Write-Host "[soak] $Device, $($samples.Count) samples ($([int]$samples.Count) min): $entries different queue entries played,"
    Write-Host "[soak] underruns (counter at the end): $($samples[-1].underruns); underrun log lines: $underrunLines; processes seen: $pids (start pid $pid0)"
    Write-Host "[soak] longest stretch without the position moving: $maxStill min; still playing at the end: $($samples[-1].playing)"
    Write-Host "[soak] samples: $out"
} finally {
    & $dev -Device $Device -Cmd pause -WaitMs 300 | Out-Null
    Exit-TfDeviceLock $serial
}
