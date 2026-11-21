# Dot-source this from every script. Per-process environment for Trueform builds and device runs; it never changes
# system or user settings. Rules it implements: CLAUDE.md ("Rules", "Shared phones").

$ErrorActionPreference = 'Stop'

$TF_ROOT = Split-Path -Parent $PSScriptRoot
$TF_JDK  = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'   # JAVA_HOME on this PC may be stale
$TF_GIT  = 'C:\Program Files\Git\cmd\git.exe'                          # PATH git is an MSYS2 build
$TF_SDK  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$TF_ADB  = Join-Path $TF_SDK 'platform-tools\adb.exe'
$TF_APP_ID = 'dev.trueform.player.debug'
$TF_ACTIVITY = "$TF_APP_ID/dev.trueform.player.MainActivity"
$TF_LOCK_DIR = 'D:\Stuff\DevFolder\Android_Games\Raveform\captures\locks'   # shared with the other projects' sessions
$TF_CAPTURES = Join-Path $TF_ROOT 'captures'

foreach ($p in @($TF_JDK, $TF_GIT, $TF_ADB)) {
    if (-not (Test-Path $p)) { throw "Missing tool: $p" }
}
$env:JAVA_HOME = $TF_JDK
$env:ANDROID_HOME = $TF_SDK

# --- CPU limit: at most 4 cores for everything this project runs on the PC ---------------------------------------
# This PowerShell process is confined to one logical CPU on each of 4 physical cores of one L3 domain (core 0 left
# free); every process it starts inherits that: Gradle and its daemon, the Kotlin compiler, CMake, clang. Per
# process only. (Ported from Raveform's scripts\env.ps1.)
$TF_JOBS = 4
if (-not ('TfCpuTopology' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class TfCpuTopology {
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetLogicalProcessorInformation(IntPtr buffer, ref uint length);
    public static void Read(List<ulong> cores, List<ulong> l3) {
        uint len = 0;
        GetLogicalProcessorInformation(IntPtr.Zero, ref len);
        if (len == 0) return;
        IntPtr buf = Marshal.AllocHGlobal((int)len);
        try {
            if (!GetLogicalProcessorInformation(buf, ref len)) return;
            int size = IntPtr.Size == 8 ? 32 : 24;
            int unionOffset = IntPtr.Size == 8 ? 16 : 8;
            for (int off = 0; off + size <= (int)len; off += size) {
                ulong mask = (ulong)Marshal.ReadIntPtr(buf, off).ToInt64();
                int relation = Marshal.ReadInt32(buf, off + IntPtr.Size);
                if (relation == 0) cores.Add(mask);
                else if (relation == 2 && Marshal.ReadByte(buf, off + unionOffset) == 3) l3.Add(mask);
            }
        } finally { Marshal.FreeHGlobal(buf); }
    }
}
'@
}
function Get-TfCpuMask([int]$Cores = $TF_JOBS) {
    $coreMasks = [System.Collections.Generic.List[uint64]]::new()
    $l3Masks = [System.Collections.Generic.List[uint64]]::new()
    [TfCpuTopology]::Read($coreMasks, $l3Masks)
    if ($coreMasks.Count -eq 0) { return [uint64]0 }
    $domains = if ($l3Masks.Count) { @($l3Masks) } else { @([uint64]::MaxValue) }
    for ($d = $domains.Count - 1; $d -ge 0; --$d) {
        $inDomain = @($coreMasks | Where-Object { ($_ -band $domains[$d]) -eq $_ })
        if ($inDomain.Count -lt $Cores) { continue }
        [uint64]$mask = 0
        foreach ($core in ($inDomain | Select-Object -Last $Cores)) {
            for ($bit = 0; $bit -lt 64; ++$bit) {
                if (($core -shr $bit) -band 1) { $mask = $mask -bor ([uint64]1 -shl $bit); break }
            }
        }
        return $mask
    }
    return [uint64]0
}
$tfMask = Get-TfCpuMask
$tfSelf = [System.Diagnostics.Process]::GetCurrentProcess()
[uint64]$tfCurrent = $tfSelf.ProcessorAffinity.ToInt64()
$tfBits = 0; for ($b = 0; $b -lt 64; ++$b) { if (($tfCurrent -shr $b) -band 1) { ++$tfBits } }
if ($tfMask -ne 0 -and $tfBits -gt $TF_JOBS) {
    $tfSelf.ProcessorAffinity = [IntPtr]::new([int64]$tfMask)   # never widened if already narrower
}

# --- git ---------------------------------------------------------------------------------------------------------
# The user's ~/.gitconfig points http.sslcainfo at a missing file: HTTPS goes through the Windows certificate store.
function Invoke-TfGit {
    & $TF_GIT -c http.sslBackend=schannel -c advice.detachedHead=false @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed ($LASTEXITCODE)" }
}

# --- devices -----------------------------------------------------------------------------------------------------
# Names to hardware serials (ro.serialno) live in the untracked scripts\devices.local.ps1 ($TF_DEVICES).
$TF_DEVICES = @{}
$devicesFile = Join-Path $PSScriptRoot 'devices.local.ps1'
if (Test-Path $devicesFile) { . $devicesFile }

$TF_DEVICE_IDS = @{}
function Get-TfDeviceId {
    param([Parameter(Mandatory)][string]$Serial)
    if ($Serial -notmatch '[:]|_adb-tls-connect') { return $Serial }
    if (-not $TF_DEVICE_IDS.ContainsKey($Serial)) {
        $id = "$(& $TF_ADB -s $Serial shell getprop ro.serialno 2>$null)".Trim()
        $TF_DEVICE_IDS[$Serial] = if ($id) { $id } else { $Serial -replace '[^\w.-]', '_' }
    }
    return $TF_DEVICE_IDS[$Serial]
}

# The adb serial for a device name or hardware serial. Wireless debugging ports change, so a Wi-Fi transport is
# matched by its ro.serialno; one not connected is looked up by mDNS and connected.
function Resolve-TfSerial {
    param([Parameter(Mandatory)][string]$NameOrSerial)
    if ($NameOrSerial -match ':') { return $NameOrSerial }
    $wanted = @(if ($TF_DEVICES.ContainsKey($NameOrSerial)) { $TF_DEVICES[$NameOrSerial] } else { $NameOrSerial })
    $attached = @(& $TF_ADB devices 2>$null | Select-Object -Skip 1 |
        Where-Object { $_ -match '^(\S+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    foreach ($w in $wanted) { if ($attached -contains $w) { return $w } }
    foreach ($w in $wanted) {
        foreach ($t in @($attached | Where-Object { $_ -match '[:]|_adb-tls-connect' })) {
            if ((Get-TfDeviceId $t) -eq $w) { return $t }
        }
    }
    $mdns = @(& $TF_ADB mdns services 2>$null)
    foreach ($w in $wanted) {
        $hit = $mdns | Where-Object { $_ -match "^adb-$([regex]::Escape($w))-\S*\s+_adb-tls-connect\._tcp\s+(\S+)" } |
            Select-Object -First 1
        if ($hit -and $hit -match '_adb-tls-connect\._tcp\s+(\S+)') {
            $addr = $Matches[1]
            & $TF_ADB connect $addr 2>&1 | Out-Null
            if ((& $TF_ADB -s $addr get-state 2>$null) -eq 'device') { return $addr }
        }
    }
    throw "device '$NameOrSerial' is not attached"
}

function Invoke-TfAdb {
    param([Parameter(Mandatory)][string]$Serial, [Parameter(ValueFromRemainingArguments)][string[]]$Rest)
    & $TF_ADB -s $Serial @Rest
}

function Get-TfForegroundPackage {
    param([Parameter(Mandatory)][string]$Serial)
    $line = (& $TF_ADB -s $Serial shell "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'") |
        Select-Object -First 1
    if ($line -match 'u0 ([\w\.]+)/') { return $Matches[1] }
    return ''
}

function Get-TfHomePackage {
    param([Parameter(Mandatory)][string]$Serial)
    $line = (& $TF_ADB -s $Serial shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') |
        Select-Object -Last 1
    return ($line -split '/')[0].Trim()
}

# True when the launcher or Trueform (debug or release) is in front: nobody else is using the phone. Nothing in front
# (screen off, or the lock screen up) is not free: input would land on the lock screen.
function Test-TfDeviceFree {
    param([Parameter(Mandatory)][string]$Serial)
    $fg = Get-TfForegroundPackage $Serial
    return ($fg -ne '' -and ($fg -eq (Get-TfHomePackage $Serial) -or $fg -like 'dev.trueform.player*'))
}

function Get-TfPid {
    param([Parameter(Mandatory)][string]$Serial, [string]$Package = $TF_APP_ID)
    return "$(& $TF_ADB -s $Serial shell pidof $Package)".Trim()
}

function Stop-TfApp {
    param([Parameter(Mandatory)][string]$Serial, [string]$Package = $TF_APP_ID)
    if ($Package -notlike 'dev.trueform.player*') { throw "refusing to stop $Package" }
    & $TF_ADB -s $Serial shell am force-stop $Package | Out-Null
}

# Lock files shared with the other projects' sessions: a lock younger than 30 minutes with another name means busy.
function Enter-TfDeviceLock {
    param([Parameter(Mandatory)][string]$Serial, [int]$WaitSeconds = 600)
    New-Item -ItemType Directory -Force $TF_LOCK_DIR | Out-Null
    $file = Join-Path $TF_LOCK_DIR "$(Get-TfDeviceId $Serial).lock"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while (Test-Path $file) {
        $item = Get-Item $file
        $owner = "$(Get-Content $file -ErrorAction SilentlyContinue)"
        if ($owner -like 'trueform*' -or ((Get-Date) - $item.LastWriteTime).TotalMinutes -ge 30) { break }
        if ((Get-Date) -gt $deadline) { throw "$Serial is locked by '$owner'" }
        Start-Sleep -Seconds 10
    }
    "trueform $(Get-Date -Format s)" | Set-Content $file
}
function Exit-TfDeviceLock {
    param([Parameter(Mandatory)][string]$Serial)
    $file = Join-Path $TF_LOCK_DIR "$(Get-TfDeviceId $Serial).lock"
    if ((Test-Path $file) -and "$(Get-Content $file)" -like 'trueform*') { Remove-Item $file -Force }
}

# The owner asked (2026-11-20) to keep the Moto's and the 12R's screens awake during testing: wake before use.
function Wake-TfDevice {
    param([Parameter(Mandatory)][string]$Serial)
    & $TF_ADB -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
}
