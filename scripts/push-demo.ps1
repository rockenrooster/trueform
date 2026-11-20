# Copies the synthetic demo library (scripts/make-demo-music.sh) to a test phone at /storage/emulated/0/TrueformTest:
# outside Music/, so music apps and folder sync on the phone do not pick it up by default.
#   scripts\push-demo.ps1 -Device moto [-Remove]
param([Parameter(Mandatory)][string]$Device, [switch]$Remove)
. "$PSScriptRoot\env.ps1"
$serial = Resolve-TfSerial $Device
$target = '/storage/emulated/0/TrueformTest'
if ($Remove) {
    & $TF_ADB -s $serial shell rm -rf $target
    Write-Host "[demo] removed $target from $Device"
    return
}
$src = Join-Path $TF_CAPTURES 'demo\TrueformTest'
if (-not (Test-Path $src)) { throw "no demo library: run scripts/make-demo-music.sh in WSL" }
& $TF_ADB -s $serial shell mkdir -p $target
& $TF_ADB -s $serial push "$src\." $target | Select-Object -Last 1
Write-Host "[demo] $src -> $Device $target"
