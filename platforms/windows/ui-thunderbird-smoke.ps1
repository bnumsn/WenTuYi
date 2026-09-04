param(
    [string] $OutPath = "C:\Temp\wentuyi\ui-thunderbird-smoke.out",
    [string] $Passphrase = "thunderbird-key"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InsertScript = Join-Path $ScriptDir "wentuyi-insert.ps1"
$Profile = Join-Path $ScriptDir "thunderbird-profile"
$ThunderbirdCandidates = @(
    "C:\Program Files\Mozilla Thunderbird\thunderbird.exe",
    "C:\Program Files (x86)\Mozilla Thunderbird\thunderbird.exe",
    "$env:LOCALAPPDATA\Mozilla Thunderbird\thunderbird.exe"
)
$Thunderbird = $ThunderbirdCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Thunderbird) { throw "Thunderbird executable not found" }

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiThunderbirdSmokeNative {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", EntryPoint="FindWindowW", CharSet=CharSet.Unicode)]
    public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);

    [DllImport("user32.dll")]
    public static extern bool SetCursorPos(int X, int Y);

    [DllImport("user32.dll")]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
}
"@

function Wait-Until([scriptblock] $Condition, [int] $TimeoutMs, [string] $Message) {
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        [System.Windows.Forms.Application]::DoEvents()
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 150
    }
    throw $Message
}

function Get-ThunderbirdWindow {
    Get-Process thunderbird -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero -and ($_.MainWindowTitle -match "Write|Compose|Wentuyi") } |
        Select-Object -First 1
}

function Focus-Body([IntPtr] $Handle) {
    [void][WentuyiThunderbirdSmokeNative]::ShowWindow($Handle, 9)
    [void][WentuyiThunderbirdSmokeNative]::MoveWindow($Handle, 80, 60, 1100, 760, $true)
    [void][WentuyiThunderbirdSmokeNative]::SetForegroundWindow($Handle)
    Start-Sleep -Milliseconds 500
    [void][WentuyiThunderbirdSmokeNative]::SetCursorPos(610, 610)
    [WentuyiThunderbirdSmokeNative]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [WentuyiThunderbirdSmokeNative]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 300
}

function Copy-FocusedText {
    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.Clipboard]::Clear()
    [System.Windows.Forms.SendKeys]::SendWait("^c")
    Wait-Until { [System.Windows.Forms.Clipboard]::ContainsText() } 6000 "clipboard did not receive focused text"
    return [System.Windows.Forms.Clipboard]::GetText()
}

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

$oldPassphrase = $env:WENTUYI_PASSPHRASE
try {
    Set-PortableJavaRuntime
    Get-Process thunderbird -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 800
    Remove-Item -LiteralPath $Profile -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $Profile | Out-Null
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
    ) | Set-Content -LiteralPath (Join-Path $Profile "user.js") -Encoding ASCII

    $env:WENTUYI_PASSPHRASE = $Passphrase
    $compose = "to='test@example.invalid',subject='Wentuyi Thunderbird smoke',body='prefix  suffix'"
    $proc = Start-Process -FilePath $Thunderbird -ArgumentList @("-no-remote", "-profile", $Profile, "-compose", $compose) -PassThru
    Wait-Until { Get-ThunderbirdWindow } 30000 "Thunderbird compose window did not appear"
    $windowProc = Get-ThunderbirdWindow
    for ($i = 0; $i -lt 20; $i++) {
        $integration = [WentuyiThunderbirdSmokeNative]::FindWindow($null, "System Integration")
        if ($integration -ne [IntPtr]::Zero) {
            [void][WentuyiThunderbirdSmokeNative]::SetForegroundWindow($integration)
            Start-Sleep -Milliseconds 300
            [System.Windows.Forms.SendKeys]::SendWait("{TAB}")
            Start-Sleep -Milliseconds 250
            [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
            Start-Sleep -Milliseconds 700
            break
        }
        Start-Sleep -Milliseconds 200
    }
    Focus-Body $windowProc.MainWindowHandle

    [System.Windows.Forms.SendKeys]::SendWait("^a")
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait("{BACKSPACE}")
    Start-Sleep -Milliseconds 300
    & $InsertScript -EncryptText "thunderbird mail rich"
    if ($LASTEXITCODE -ne 0) { throw "wentuyi encrypted insert failed" }
    Start-Sleep -Milliseconds 800
    $encrypted = Copy-FocusedText
    if (-not $encrypted.StartsWith("WTY4:")) { throw "unexpected encrypted text: $encrypted" }

    [System.Windows.Forms.SendKeys]::SendWait("{BACKSPACE}")
    Start-Sleep -Milliseconds 300
    & $InsertScript -DecryptText $encrypted
    if ($LASTEXITCODE -ne 0) { throw "wentuyi decrypted insert failed" }
    Start-Sleep -Milliseconds 800
    $decrypted = Copy-FocusedText
    if ($decrypted -ne "thunderbird mail rich") { throw "unexpected decrypted text: $decrypted" }

    @(
        "windows-thunderbird-encrypted-prefix=WTY4:",
        "windows-thunderbird-decrypted=thunderbird mail rich"
    ) | Out-File -LiteralPath $OutPath -Encoding UTF8
    exit 0
} catch {
    @(
        "error-record:",
        ($_ | Format-List * -Force | Out-String),
        "script-stack:",
        $_.ScriptStackTrace
    ) | Out-File -LiteralPath $OutPath -Encoding UTF8
    exit 1
} finally {
    $env:WENTUYI_PASSPHRASE = $oldPassphrase
}
