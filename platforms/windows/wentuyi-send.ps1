param(
    [ValidateSet("focused", "thunderbird")]
    [string] $App = "focused",
    [string] $To = "test@example.invalid",
    [string] $Subject = "Wentuyi direct send",
    [string] $Text,
    [string] $EncryptText,
    [string] $PlainImage,
    [string] $EncryptedQr,
    [string] $OutDir,
    [string] $PassphraseFile = "$env:APPDATA\Wentuyi\passphrase.txt"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CliScript = Join-Path $ScriptDir "wentuyi-cli.ps1"
$InsertScript = Join-Path $ScriptDir "wentuyi-insert.ps1"
if (-not $OutDir) { $OutDir = Join-Path $env:TEMP ("wentuyi-send-" + [Guid]::NewGuid().ToString("N")) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Set-PortableJavaRuntime {
    if (Get-Command java.exe -ErrorAction SilentlyContinue) { return }
    $jreZip = Join-Path $ScriptDir "jre-windows.zip"
    $jreRoot = Join-Path $ScriptDir "jre-ui"
    if (-not (Test-Path $jreZip)) { return }
    if (-not (Test-Path (Join-Path $jreRoot "bin\java.exe"))) {
        Remove-Item -LiteralPath $jreRoot -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $jreRoot | Out-Null
        Expand-Archive -LiteralPath $jreZip -DestinationPath $jreRoot -Force
        $java = Get-ChildItem -Path $jreRoot -Filter java.exe -Recurse | Select-Object -First 1
        if (-not $java) { throw "jre-windows.zip did not contain java.exe" }
        $actualHome = (Resolve-Path (Join-Path $java.DirectoryName ".." )).Path
        if ($actualHome -ne $jreRoot) {
            Get-ChildItem -LiteralPath $actualHome -Force | Move-Item -Destination $jreRoot -Force
        }
    }
    $env:WENTUYI_JAVA_HOME = $jreRoot
    $env:JAVA_HOME = $jreRoot
    $env:PATH = (Join-Path $jreRoot "bin") + ";" + $env:PATH
}

function Set-DesktopCliRuntime {
    $cli = Join-Path $ScriptDir "desktop-cli\bin\desktop-cli.bat"
    $zxing = Join-Path $ScriptDir "desktop-cli\lib\core-3.5.3.jar"
    $cliZip = Join-Path $ScriptDir "wentuyi-desktop-cli.zip"
    if ((-not (Test-Path $cli) -or -not (Test-Path $zxing)) -and (Test-Path $cliZip)) {
        Remove-Item -LiteralPath (Join-Path $ScriptDir "desktop-cli") -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive -LiteralPath $cliZip -DestinationPath $ScriptDir -Force
    }
    if (Test-Path $cli) { $env:WENTUYI_CLI = $cli }
}

function Get-WentuyiPassphrase {
    if ($env:WENTUYI_PASSPHRASE) { return $env:WENTUYI_PASSPHRASE }
    if (Test-Path $PassphraseFile) {
        $value = (Get-Content -LiteralPath $PassphraseFile -Raw).Trim()
        if ($value) { return $value }
    }
    throw "Set WENTUYI_PASSPHRASE or create $PassphraseFile"
}

function Invoke-WentuyiCli([string[]] $ArgsList) {
    Set-PortableJavaRuntime
    Set-DesktopCliRuntime
    $output = & $CliScript @ArgsList
    if ($LASTEXITCODE -ne 0) { throw "desktop-cli failed: $($output -join "`n")" }
    return @($output | Where-Object { $_ -and $_.Trim() })
}

function Get-ThunderbirdExe {
    $candidates = @(
        "C:\Program Files\Mozilla Thunderbird\thunderbird.exe",
        "C:\Program Files (x86)\Mozilla Thunderbird\thunderbird.exe",
        "$env:LOCALAPPDATA\Mozilla Thunderbird\thunderbird.exe"
    )
    $exe = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) { throw "Thunderbird executable not found" }
    return $exe
}

function Get-ThunderbirdProfile {
    $profile = Join-Path $ScriptDir "thunderbird-send-profile"
    New-Item -ItemType Directory -Force -Path $profile | Out-Null
    @(
        'user_pref("mail.shell.checkDefaultClient", false);',
        'user_pref("mail.provider.suppress_dialog_on_startup", true);',
        'user_pref("app.normandy.first_run", false);',
        'user_pref("browser.shell.checkDefaultBrowser", false);',
        'user_pref("mail.account.account1.identities", "id1");',
        'user_pref("mail.account.account1.server", "server1");',
        'user_pref("mail.accountmanager.accounts", "account1");',
        'user_pref("mail.accountmanager.defaultaccount", "account1");',
        'user_pref("mail.identity.id1.fullName", "Wentuyi Test");',
        'user_pref("mail.identity.id1.useremail", "wentuyi@example.invalid");',
        'user_pref("mail.identity.id1.smtpServer", "smtp1");',
        'user_pref("mail.server.server1.hostname", "pop.example.invalid");',
        'user_pref("mail.server.server1.name", "wentuyi@example.invalid");',
        'user_pref("mail.server.server1.type", "pop3");',
        'user_pref("mail.server.server1.userName", "wentuyi");',
        'user_pref("mail.smtpserver.smtp1.authMethod", 1);',
        'user_pref("mail.smtpserver.smtp1.hostname", "smtp.example.invalid");',
        'user_pref("mail.smtpserver.smtp1.port", 25);',
        'user_pref("mail.smtpserver.smtp1.try_ssl", 0);',
        'user_pref("mail.smtpserver.smtp1.username", "wentuyi");',
        'user_pref("mail.smtpservers", "smtp1");'
    ) | Set-Content -LiteralPath (Join-Path $profile "user.js") -Encoding ASCII
    return $profile
}

function Convert-ToFileUri([string] $Path) {
    return ([Uri](Resolve-Path -LiteralPath $Path).Path).AbsoluteUri
}

function Send-Body([string] $Body) {
    if ($App -eq "focused") {
        & $InsertScript -Text $Body
        if ($LASTEXITCODE -ne 0) { throw "focused text insert failed" }
        return
    }
    if ($App -eq "thunderbird") {
        $exe = Get-ThunderbirdExe
        $profile = Get-ThunderbirdProfile
        $compose = "to='$To',subject='$Subject',body='$Body'"
        Start-Process -FilePath $exe -ArgumentList @("-no-remote", "-profile", $profile, "-compose", $compose) | Out-Null
        return
    }
    throw "unsupported app: $App"
}

function Send-Files([string[]] $Files) {
    if ($Files.Count -eq 0) { throw "no generated files" }
    if ($App -ne "thunderbird") {
        throw "desktop image direct-send is app-specific; use -App thunderbird"
    }
    $attachments = ($Files | ForEach-Object { Convert-ToFileUri $_ }) -join ","
    $exe = Get-ThunderbirdExe
    $profile = Get-ThunderbirdProfile
    $compose = "to='$To',subject='$Subject',attachment='$attachments'"
    Start-Process -FilePath $exe -ArgumentList @("-no-remote", "-profile", $profile, "-compose", $compose) | Out-Null
    $Files | ForEach-Object { Write-Output $_ }
}

$set = @($Text, $EncryptText, $PlainImage, $EncryptedQr).Where({ -not [string]::IsNullOrEmpty($_) }).Count
if ($set -ne 1) { throw "Specify exactly one of -Text, -EncryptText, -PlainImage, or -EncryptedQr" }

if ($Text) { Send-Body $Text; return }
if ($EncryptText) {
    $payload = (Invoke-WentuyiCli @("encrypt-text", "--passphrase", (Get-WentuyiPassphrase), $EncryptText))[0]
    Send-Body $payload
    return
}
if ($PlainImage) {
    $out = Join-Path $OutDir "wentuyi-plain.png"
    Invoke-WentuyiCli @("plain-image", "--out", $out, $PlainImage) | Out-Null
    Send-Files @($out)
    return
}
if ($EncryptedQr) {
    $files = Invoke-WentuyiCli @("encrypted-qr", "--passphrase", (Get-WentuyiPassphrase), "--out-dir", $OutDir, "--prefix", "wentuyi-qr", $EncryptedQr)
    Send-Files $files
    return
}
