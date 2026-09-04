param(
    [string] $Text,
    [string] $EncryptText,
    [string] $DecryptText,
    # Contact from the profile under WENTUYI_HOME; enables the forward-secret WTY5 path.
    [string] $Peer = $env:WENTUYI_PEER,
    [IntPtr] $TargetHwnd = [IntPtr]::Zero,
    [string] $CliScript,
    [string] $PassphraseFile = "$env:APPDATA\Wentuyi\passphrase.txt",
    [switch] $SelfTest
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $CliScript) { $CliScript = Join-Path $ScriptDir "wentuyi-cli.ps1" }

# NOTE: no "-"/stdin form here. PowerShell can't reliably deliver piped stdin to a param-block
# script (real-PS-5.1 testing: in-process object pipes hang on [Console]::In; OS pipes read
# empty), so the secret-clean path on Windows is the hotkey → Invoke-WentuyiCli (passphrase via
# env, text via the wentuyi-cli.ps1 stdin forward). Direct -EncryptText puts that one plaintext
# on this process' argv; use the hotkey for content you don't want on a command line.

if (-not ("WentuyiDirectInsertNative" -as [type])) {
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiDirectInsertNative {
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT {
        public UInt32 type;
        public INPUTUNION u;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct INPUTUNION {
        [FieldOffset(0)]
        public MOUSEINPUT mi;

        [FieldOffset(0)]
        public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT {
        public Int32 dx;
        public Int32 dy;
        public UInt32 mouseData;
        public UInt32 dwFlags;
        public UInt32 time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT {
        public UInt16 wVk;
        public UInt16 wScan;
        public UInt32 dwFlags;
        public UInt32 time;
        public UIntPtr dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern UInt32 SendInput(UInt32 nInputs, INPUT[] pInputs, Int32 cbSize);

    [DllImport("user32.dll", EntryPoint = "SendMessageW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr SendMessage(IntPtr hWnd, UInt32 msg, IntPtr wParam, string lParam);

    private const UInt32 INPUT_KEYBOARD = 1;
    private const UInt32 KEYEVENTF_KEYUP = 0x0002;
    private const UInt32 KEYEVENTF_UNICODE = 0x0004;
    private const UInt32 EM_REPLACESEL = 0x00C2;

    public static void InsertText(IntPtr targetHwnd, string text) {
        if (targetHwnd != IntPtr.Zero) {
            ReplaceSelection(targetHwnd, text);
            return;
        }
        InsertText(text);
    }

    public static void ReplaceSelection(IntPtr targetHwnd, string text) {
        if (targetHwnd == IntPtr.Zero) { throw new ArgumentException("targetHwnd is zero"); }
        SendMessage(targetHwnd, EM_REPLACESEL, new IntPtr(1), text ?? "");
    }

    public static void InsertText(string text) {
        if (String.IsNullOrEmpty(text)) { return; }
        foreach (char ch in text) {
            INPUT down = new INPUT();
            down.type = INPUT_KEYBOARD;
            down.u.ki.wVk = 0;
            down.u.ki.wScan = ch;
            down.u.ki.dwFlags = KEYEVENTF_UNICODE;

            INPUT up = down;
            up.u.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;

            INPUT[] inputs = new INPUT[] { down, up };
            UInt32 sent = SendInput((UInt32)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
            if (sent != inputs.Length) {
                throw new InvalidOperationException("SendInput failed: " + Marshal.GetLastWin32Error());
            }
        }
    }
}
"@
}

# -AllowMissing returns $null instead of throwing: with `send --peer` / `receive` the CLI
# reads the profile under WENTUYI_HOME, so a contact-only setup has no shared key at all.
function Get-WentuyiPassphrase([switch] $AllowMissing) {
    if ($env:WENTUYI_PASSPHRASE) { return $env:WENTUYI_PASSPHRASE }
    if (Test-Path $PassphraseFile) {
        $value = (Get-Content -LiteralPath $PassphraseFile -Raw).Trim()
        if ($value) { return $value }
    }
    if ($AllowMissing) { return $null }
    throw "Set WENTUYI_PASSPHRASE or create $PassphraseFile"
}

# Secret via env, text via stdin (--stdin) — keeps both off the child process command line.
function Invoke-WentuyiCli([string[]] $ArgsList, [string] $Passphrase = $null, [string] $StdinText = $null) {
    $prev = $env:WENTUYI_PASSPHRASE
    try {
        if ($Passphrase) { $env:WENTUYI_PASSPHRASE = $Passphrase }
        if ($null -ne $StdinText) {
            $cliArgs = @($ArgsList) + "--stdin"
            $output = $StdinText | & $CliScript @cliArgs   # @-splat so $args flattens in the wrapper
        } else {
            $output = & $CliScript @ArgsList
        }
        if ($LASTEXITCODE -ne 0) { throw "desktop-cli failed: $($output -join "`n")" }
        return ($output -join "`n").Trim()
    } finally {
        if ($null -eq $prev) { Remove-Item Env:\WENTUYI_PASSPHRASE -ErrorAction SilentlyContinue }
        else { $env:WENTUYI_PASSPHRASE = $prev }
    }
}

if ($SelfTest) {
    [WentuyiDirectInsertNative]::InsertText($TargetHwnd, "")
    Write-Output "direct-insert-self-test=available"
    return
}

$set = @($Text, $EncryptText, $DecryptText).Where({ -not [string]::IsNullOrEmpty($_) }).Count
if ($set -ne 1) {
    throw "Specify exactly one of -Text, -EncryptText, or -DecryptText"
}

if ($Text) {
    [WentuyiDirectInsertNative]::InsertText($TargetHwnd, $Text)
    return
}

# The passphrase is optional now: `send -Peer` and `receive` read the profile instead, so
# a contact-only setup must not fail for lack of a shared key.
$passphrase = Get-WentuyiPassphrase -AllowMissing

if ($EncryptText) {
    # `send` picks the protocol (WTY5 ratchet > WTY4 session key > shared passphrase);
    # making that choice in PowerShell is what kept this bridge shared-key-only.
    $sendArgs = @("send")
    if ($Peer) { $sendArgs += @("--peer", $Peer) }
    $payload = Invoke-WentuyiCli $sendArgs -Passphrase $passphrase -StdinText $EncryptText
    [WentuyiDirectInsertNative]::InsertText($TargetHwnd, $payload)
    return
}

if ($DecryptText) {
    # `receive` auto-detects WTY5 / WTY4-session / WTY4-passphrase and the sender.
    $plain = Invoke-WentuyiCli @("receive") -Passphrase $passphrase -StdinText $DecryptText
    [WentuyiDirectInsertNative]::InsertText($TargetHwnd, $plain)
    return
}
