param(
    [switch] $SelfTest,
    [string] $CliScript,
    [string] $PassphraseFile = "$env:APPDATA\Wentuyi\passphrase.txt"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiHotkeyInputNative {
    [DllImport("user32.dll")]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
}
"@
if (-not $CliScript) { $CliScript = Join-Path $ScriptDir "wentuyi-cli.ps1" }

function Get-WentuyiPassphrase {
    if ($env:WENTUYI_PASSPHRASE) { return $env:WENTUYI_PASSPHRASE }
    if (Test-Path $PassphraseFile) {
        $value = (Get-Content -LiteralPath $PassphraseFile -Raw).Trim()
        if ($value) { return $value }
    }
    throw "Set WENTUYI_PASSPHRASE or create $PassphraseFile"
}

function Invoke-WentuyiCli([string[]] $ArgsList) {
    $output = & $CliScript @ArgsList
    if ($LASTEXITCODE -ne 0) { throw "desktop-cli failed: $($output -join "`n")" }
    return ($output -join "`n").Trim()
}

function Write-HotkeyLog([string] $Message) {
    if (-not $env:WENTUYI_HOTKEY_LOG) { return }
    $stamp = Get-Date -Format o
    Add-Content -LiteralPath $env:WENTUYI_HOTKEY_LOG -Value "$stamp $Message"
}

function Send-CtrlKey([byte] $VirtualKey, [int] $DelayMs = 180) {
    $KEYEVENTF_KEYUP = 0x0002
    $VK_CONTROL = 0x11
    [WentuyiHotkeyInputNative]::keybd_event($VK_CONTROL, 0, 0, [UIntPtr]::Zero)
    [WentuyiHotkeyInputNative]::keybd_event($VirtualKey, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
    [WentuyiHotkeyInputNative]::keybd_event($VirtualKey, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    [WentuyiHotkeyInputNative]::keybd_event($VK_CONTROL, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds $DelayMs
}

function Convert-WentuyiText([string] $Mode, [string] $Text) {
    if (-not $Text) { throw "No selected or clipboard text" }
    $passphrase = Get-WentuyiPassphrase
    if ($Mode -eq "encrypt") {
        return Invoke-WentuyiCli @("encrypt-text", "--passphrase", $passphrase, $Text)
    }
    if ($Mode -eq "decrypt") {
        return Invoke-WentuyiCli @("decrypt-text", "--passphrase", $passphrase, $Text)
    }
    throw "Unknown mode: $Mode"
}

function Invoke-ClipboardTransform([string] $Mode) {
    Add-Type -AssemblyName System.Windows.Forms
    Write-HotkeyLog "transform-start mode=$Mode"
    [System.Windows.Forms.Clipboard]::Clear()
    Send-CtrlKey 0x43 220
    $text = Get-Clipboard -Raw
    if ($null -eq $text) { $text = "" }
    Write-HotkeyLog "clipboard-read mode=$Mode length=$($text.Length)"
    $result = Convert-WentuyiText $Mode $text
    Write-HotkeyLog "transform-result mode=$Mode prefix=$($result.Substring(0, [Math]::Min(5, $result.Length)))"
    Set-Clipboard -Value $result
    Start-Sleep -Milliseconds 80
    Send-CtrlKey 0x56 120
    Write-HotkeyLog "transform-pasted mode=$Mode"
}

if ($SelfTest) {
    $old = $env:WENTUYI_PASSPHRASE
    try {
        $env:WENTUYI_PASSPHRASE = "hotkey-test"
        $payload = Convert-WentuyiText "encrypt" "windows hotkey"
        $plain = Convert-WentuyiText "decrypt" $payload
        if ($plain -ne "windows hotkey") { throw "unexpected plaintext: $plain" }
        Write-Output "hotkey-self-test=windows hotkey"
        exit 0
    } finally {
        $env:WENTUYI_PASSPHRASE = $old
    }
}

Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiHotKeyNative {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll")]
    public static extern sbyte GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [StructLayout(LayoutKind.Sequential)]
    public struct MSG {
        public IntPtr hwnd;
        public uint message;
        public UIntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int pt_x;
        public int pt_y;
    }
}
"@

$MOD_ALT = 0x0001
$MOD_CONTROL = 0x0002
$WM_HOTKEY = 0x0312
$VK_D = 0x44
$VK_E = 0x45

if (-not [WentuyiHotKeyNative]::RegisterHotKey([IntPtr]::Zero, 1, $MOD_CONTROL -bor $MOD_ALT, $VK_E)) {
    throw "Failed to register Ctrl+Alt+E"
}
if (-not [WentuyiHotKeyNative]::RegisterHotKey([IntPtr]::Zero, 2, $MOD_CONTROL -bor $MOD_ALT, $VK_D)) {
    [void][WentuyiHotKeyNative]::UnregisterHotKey([IntPtr]::Zero, 1)
    throw "Failed to register Ctrl+Alt+D"
}

Write-Output "Wentuyi hotkeys active: Ctrl+Alt+E encrypts selection, Ctrl+Alt+D decrypts selection. Close this window to stop."
Write-HotkeyLog "hotkeys-active"
try {
    $msg = New-Object WentuyiHotKeyNative+MSG
    while ([WentuyiHotKeyNative]::GetMessage([ref] $msg, [IntPtr]::Zero, 0, 0) -ne 0) {
        if ($msg.message -ne $WM_HOTKEY) { continue }
        try {
            Write-HotkeyLog "hotkey-message id=$($msg.wParam.ToUInt32())"
            if ($msg.wParam.ToUInt32() -eq 1) { Invoke-ClipboardTransform "encrypt" }
            if ($msg.wParam.ToUInt32() -eq 2) { Invoke-ClipboardTransform "decrypt" }
        } catch {
            Write-HotkeyLog "hotkey-error $($_.Exception.Message)"
            [Console]::Error.WriteLine("Wentuyi hotkey error: $($_.Exception.Message)")
        }
    }
} finally {
    [void][WentuyiHotKeyNative]::UnregisterHotKey([IntPtr]::Zero, 1)
    [void][WentuyiHotKeyNative]::UnregisterHotKey([IntPtr]::Zero, 2)
}
