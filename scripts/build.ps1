# Builds the APK on at most 4 cores.   scripts\build.ps1 [-Config debug|staging|release] [-Task <gradle task>] [-Clean]
# staging: the test app built like a release (R8, not debuggable), for judging smoothness on the phones.
param(
    [ValidateSet('debug', 'staging', 'release')][string]$Config = 'debug',
    [string]$Task = '',
    [switch]$Clean
)
. "$PSScriptRoot\env.ps1"
Push-Location $TF_ROOT
try {
    $cfg = (Get-Culture).TextInfo.ToTitleCase($Config)
    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $tasks += if ($Task) { $Task } else { ":app:assemble$cfg" }
    & .\gradlew.bat --max-workers=4 --console=plain @tasks
    if ($LASTEXITCODE -ne 0) { throw "gradle failed ($LASTEXITCODE)" }
    if (-not $Task) {
        $apk = Get-ChildItem "app\build\outputs\apk\$Config\*.apk" | Select-Object -First 1
        Write-Host ("[build] {0} ({1:N1} MB)" -f $apk.FullName, ($apk.Length / 1MB))
    }
} finally { Pop-Location }
