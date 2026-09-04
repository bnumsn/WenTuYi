param(
    [string] $OutPath = "C:\Temp\wentuyi\ui-smoke.out",
    [string] $Passphrase = "ui-key"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$HotkeyScript = Join-Path $ScriptDir "wentuyi-hotkey.ps1"
$HotkeyStdout = Join-Path $ScriptDir "ui-hotkey.stdout"
$HotkeyStderr = Join-Path $ScriptDir "ui-hotkey.stderr"
$HotkeyLog = Join-Path $ScriptDir "ui-hotkey.log"

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
    if (-not (Test-Path $cli)) {
        $cliZip = Join-Path $ScriptDir "wentuyi-desktop-cli.zip"
        if (-not (Test-Path $cliZip)) { throw "missing desktop CLI zip: $cliZip" }
        Remove-Item -LiteralPath (Join-Path $ScriptDir "desktop-cli") -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive -LiteralPath $cliZip -DestinationPath $ScriptDir -Force
    }
    if (-not (Test-Path $cli)) { throw "missing extracted desktop CLI: $cli" }
    $env:WENTUYI_CLI = $cli
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiUiNative {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
}
"@

function Wait-ForWindow([System.Diagnostics.Process] $Process) {
    for ($i = 0; $i -lt 80; $i++) {
        $Process.Refresh()
        if ($Process.MainWindowHandle -ne [IntPtr]::Zero) { return $Process.MainWindowHandle }
        Start-Sleep -Milliseconds 100
    }
    throw "window did not appear for process $($Process.Id)"
}

function Focus-Window([IntPtr] $Handle) {
    [void][WentuyiUiNative]::SetForegroundWindow($Handle)
    Start-Sleep -Milliseconds 350
}

function Send-KeyChord([string] $Chord, [int] $DelayMs = 300) {
    [System.Windows.Forms.SendKeys]::SendWait($Chord)
    Start-Sleep -Milliseconds $DelayMs
}

function Send-RegisteredHotkey([byte] $VirtualKey, [int] $DelayMs = 300) {
    $KEYEVENTF_KEYUP = 0x0002
    $VK_CONTROL = 0x11
    $VK_MENU = 0x12
    [WentuyiUiNative]::keybd_event($VK_CONTROL, 0, 0, [UIntPtr]::Zero)
    [WentuyiUiNative]::keybd_event($VK_MENU, 0, 0, [UIntPtr]::Zero)
    [WentuyiUiNative]::keybd_event($VirtualKey, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WentuyiUiNative]::keybd_event($VirtualKey, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    [WentuyiUiNative]::keybd_event($VK_MENU, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    [WentuyiUiNative]::keybd_event($VK_CONTROL, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $DelayMs
}

function Read-EditorText {
    Send-KeyChord "^a" 100
    Send-KeyChord "^c" 300
    return (Get-Clipboard -Raw).TrimEnd("`r", "`n")
}

function Replace-EditorText([string] $Text) {
    Set-Clipboard -Value $Text
    Send-KeyChord "^a" 100
    Send-KeyChord "^v" 300
}

$oldPassphrase = $env:WENTUYI_PASSPHRASE
$hotkeyProcess = $null
$notepad = $null
$lines = New-Object System.Collections.Generic.List[string]

try {
    Set-PortableJavaRuntime
    Set-DesktopCliRuntime
    $env:WENTUYI_PASSPHRASE = $Passphrase
    Remove-Item -LiteralPath $HotkeyStdout, $HotkeyStderr, $HotkeyLog -Force -ErrorAction SilentlyContinue
    $env:WENTUYI_HOTKEY_LOG = $HotkeyLog
    $hotkeyProcess = Start-Process powershell.exe -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", "`"$HotkeyScript`""
    ) -PassThru -WindowStyle Minimized -RedirectStandardOutput $HotkeyStdout -RedirectStandardError $HotkeyStderr
    Start-Sleep -Seconds 2
    if ($hotkeyProcess.HasExited) {
        $stdout = if (Test-Path $HotkeyStdout) { Get-Content -LiteralPath $HotkeyStdout -Raw } else { "" }
        $stderr = if (Test-Path $HotkeyStderr) { Get-Content -LiteralPath $HotkeyStderr -Raw } else { "" }
        throw "hotkey process exited early ($($hotkeyProcess.ExitCode)): stdout=$stdout stderr=$stderr"
    }

    $notepad = Start-Process notepad.exe -PassThru
    $handle = Wait-ForWindow $notepad
    Focus-Window $handle

    Replace-EditorText "windows ui"
    Send-KeyChord "^a" 100
    Send-RegisteredHotkey 0x45 9000
    $encrypted = Read-EditorText
    if (-not $encrypted.StartsWith("WTY4:")) { throw "encrypt hotkey did not produce WTY4 payload: $encrypted" }

    Replace-EditorText $encrypted
    Send-KeyChord "^a" 100
    Send-RegisteredHotkey 0x44 5000
    $decrypted = Read-EditorText
    if ($decrypted -ne "windows ui") { throw "decrypt hotkey mismatch: $decrypted" }

    $lines.Add("windows-ui-encrypted-prefix=WTY4:")
    $lines.Add("windows-ui-decrypted=$decrypted")
    $lines | Out-File -LiteralPath $OutPath -Encoding UTF8
    exit 0
} catch {
    @(
        $lines
        "error-record:"
        ($_ | Format-List * -Force | Out-String)
        "script-stack:"
        $_.ScriptStackTrace
    ) | Out-File -LiteralPath $OutPath -Encoding UTF8
    exit 1
} finally {
    $env:WENTUYI_PASSPHRASE = $oldPassphrase
    Remove-Item Env:WENTUYI_HOTKEY_LOG -ErrorAction SilentlyContinue
    if ($notepad -and -not $notepad.HasExited) { $notepad.Kill() }
    if ($hotkeyProcess -and -not $hotkeyProcess.HasExited) { $hotkeyProcess.Kill() }
}
