# The release key (docs/RELEASE_PIPELINE.md). Creates keys\trueform-release.keystore (gitignored, never committed) with
# a random password that is never shown or written in clear: it is kept encrypted with Windows DPAPI for this user in
# keys\trueform-release.password, so only this Windows account on this PC can read it back (-ShowPassword, for the
# owner's backup). Gives the release workflow its four secrets (values go to gh's standard input, never on a command
# line or in a log) and starts a release build.
# Back up the keystore and its password off this PC: a build signed with any other key cannot update an installed copy.
#   scripts\release-key.ps1 [-SecretsOnly] [-NoBuild] [-ShowPassword]
param(
    [switch]$SecretsOnly,   # the keystore exists: only set the secrets again
    [switch]$NoBuild,       # set the secrets without starting a build
    [switch]$ShowPassword,  # print the password (the owner, for the backup; never in a scripted session)
    [string]$Repo = 'rockenrooster/trueform',
    [string]$Alias = 'trueform'
)
. "$PSScriptRoot\env.ps1"
$keys = Join-Path $TF_ROOT 'keys'
$keystore = Join-Path $keys 'trueform-release.keystore'
$pwFile = Join-Path $keys 'trueform-release.password'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$gh = (Get-Command gh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source

function Get-KeyPassword {
    $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($pwFile), $null, 'CurrentUser')
    [Text.Encoding]::UTF8.GetString($plain)
}

function Set-GhSecret([string]$Name, [string]$Value) {
    $psi = [Diagnostics.ProcessStartInfo]::new($gh)
    foreach ($a in @('secret', 'set', $Name, '-R', $Repo)) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $p = [Diagnostics.Process]::Start($psi)
    $p.StandardInput.Write($Value)
    $p.StandardInput.Close()
    $null = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "gh secret set $Name failed ($($p.ExitCode))" }
}

if ($ShowPassword) { Write-Host (Get-KeyPassword); return }

if (-not $SecretsOnly) {
    if (Test-Path $keystore) { throw "$keystore exists and may be the key in use: keep it (-SecretsOnly sets the secrets again)" }
    New-Item -ItemType Directory -Force $keys | Out-Null
    $bytes = [byte[]]::new(24)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $env:TF_KEY_NEW = [Convert]::ToBase64String($bytes).Replace('+', 'x').Replace('/', 'y')
    try {
        [IO.File]::WriteAllBytes($pwFile, [Security.Cryptography.ProtectedData]::Protect(
            [Text.Encoding]::UTF8.GetBytes($env:TF_KEY_NEW), $null, 'CurrentUser'))
        Write-Host "[key] creating $keystore (RSA 4096, 10,000 days)"
        & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias $Alias -keyalg RSA -keysize 4096 `
            -validity 10000 -dname 'CN=Trueform' -storepass:env TF_KEY_NEW -keypass:env TF_KEY_NEW 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    } finally {
        Remove-Item Env:TF_KEY_NEW -ErrorAction SilentlyContinue
    }
}
if (-not (Test-Path $keystore) -or -not (Test-Path $pwFile)) { throw "no keystore or password file in $keys" }

$env:TF_KEY_CHECK = Get-KeyPassword
try {
    $cert = & $keytool -list -v -keystore $keystore -storepass:env TF_KEY_CHECK -alias $Alias 2>&1
    if ($LASTEXITCODE -ne 0) { throw "the stored password does not open $keystore (or it has no key '$Alias')" }
    Set-GhSecret 'TF_KEYSTORE_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))
    Set-GhSecret 'TF_KEYSTORE_PASSWORD' $env:TF_KEY_CHECK
    Set-GhSecret 'TF_KEY_PASSWORD' $env:TF_KEY_CHECK   # (a PKCS12 keystore's key has the keystore's password)
    Set-GhSecret 'TF_KEY_ALIAS' $Alias
} finally {
    Remove-Item Env:TF_KEY_CHECK -ErrorAction SilentlyContinue
}
$sha = ($cert | Select-String -Pattern 'SHA256:\s*(\S+)' | Select-Object -First 1).Matches.Groups[1].Value
Write-Host "[key] the four secrets are set on $Repo; certificate SHA-256: $($sha.Replace(':', '').ToLowerInvariant())"
Write-Host "[key] back up $keystore and its password (scripts\release-key.ps1 -ShowPassword) off this PC"
if (-not $NoBuild) {
    & $gh workflow run release.yml -R $Repo --ref main
    if ($LASTEXITCODE -ne 0) { throw 'gh workflow run failed' }
    Write-Host "[key] a release build has started: gh run watch -R $Repo"
}
