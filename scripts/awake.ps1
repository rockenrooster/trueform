# Keeps the test phones' screens on while Trueform is developed (the owner's request, 2026-11-20), and puts the
# original settings back afterwards.   scripts\awake.ps1 -On [-Devices moto,op12r]   |   scripts\awake.ps1 -Off
param([switch]$On, [switch]$Off, [string[]]$Devices = @('moto', 'op12r'))
. "$PSScriptRoot\env.ps1"
New-Item -ItemType Directory -Force $TF_CAPTURES | Out-Null
$saved = Join-Path $TF_CAPTURES 'awake-original.json'
$orig = if (Test-Path $saved) { Get-Content $saved -Raw | ConvertFrom-Json -AsHashtable } else { @{} }
foreach ($d in $Devices) {
    try { $serial = Resolve-TfSerial $d } catch { Write-Host "[awake] $d is not attached, skipped"; continue }
    $id = Get-TfDeviceId $serial
    if ($On) {
        if (-not $orig.ContainsKey($id)) {
            $orig[$id] = @{
                stay_on = "$(& $TF_ADB -s $serial shell settings get global stay_on_while_plugged_in)".Trim()
                timeout = "$(& $TF_ADB -s $serial shell settings get system screen_off_timeout)".Trim()
            }
        }
        & $TF_ADB -s $serial shell svc power stayon true | Out-Null           # on while on any charger
        & $TF_ADB -s $serial shell settings put system screen_off_timeout 1800000 | Out-Null   # 30 min on battery
        Wake-TfDevice $serial
        Write-Host "[awake] $d ($id): on"
    } elseif ($Off -and $orig.ContainsKey($id)) {
        $o = $orig[$id]
        & $TF_ADB -s $serial shell settings put global stay_on_while_plugged_in $o.stay_on | Out-Null
        & $TF_ADB -s $serial shell settings put system screen_off_timeout $o.timeout | Out-Null
        $orig.Remove($id)
        Write-Host "[awake] $d ($id): restored stay_on=$($o.stay_on) timeout=$($o.timeout)"
    }
}
$orig | ConvertTo-Json | Set-Content $saved
