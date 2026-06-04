param(
    [string] $Passphrase,
    [switch] $RegisterStartup
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$ConfigDir = Join-Path $env:APPDATA "Wentuyi"
$PassphraseFile = Join-Path $ConfigDir "passphrase.txt"
$PortableJreZip = Join-Path $ScriptDir "jre-windows.zip"
$PortableJreDir = Join-Path $ScriptDir "jre"

if ((Test-Path $PortableJreZip) -and -not (Test-Path (Join-Path $PortableJreDir "bin\java.exe"))) {
    New-Item -ItemType Directory -Force -Path $PortableJreDir | Out-Null
    Expand-Archive -LiteralPath $PortableJreZip -DestinationPath $PortableJreDir -Force
    $java = Get-ChildItem -Path $PortableJreDir -Filter java.exe -Recurse | Select-Object -First 1
    if (-not $java) { throw "jre-windows.zip did not contain java.exe" }
    $actualHome = (Resolve-Path (Join-Path $java.DirectoryName ".." )).Path
    if ($actualHome -ne $PortableJreDir) {
        Get-ChildItem -LiteralPath $actualHome -Force | Move-Item -Destination $PortableJreDir -Force
    }
}

if (Test-Path (Join-Path $RepoRoot "gradlew.bat")) {
    Push-Location $RepoRoot
    try { & .\gradlew.bat :desktop-cli:installDist | Out-Host }
    finally { Pop-Location }
}

if ($Passphrase) {
    New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
    Set-Content -LiteralPath $PassphraseFile -Value $Passphrase -NoNewline -Encoding UTF8
    Write-Output "passphrase-file=$PassphraseFile"
}

$HotkeyScript = Join-Path $ScriptDir "wentuyi-hotkey.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $HotkeyScript -SelfTest

if ($RegisterStartup) {
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$HotkeyScript`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel LeastPrivilege
    Register-ScheduledTask -TaskName "WentuyiHotkeys" -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null
    Write-Output "startup-task=WentuyiHotkeys"
}

Write-Output "run-hotkeys=powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$HotkeyScript`""
