param(
    [string] $OutPath = "C:\Temp\wentuyi\ui-direct-insert-smoke.out",
    [string] $Passphrase = "direct-rich-key"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$InsertScript = Join-Path $ScriptDir "wentuyi-insert.ps1"

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class WentuyiDirectInsertSmokeNative {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern IntPtr SetFocus(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@

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

function Focus-Box($Form, $Box) {
    $Form.TopMost = $true
    [void][WentuyiDirectInsertSmokeNative]::ShowWindow($Form.Handle, 9)
    $Form.Activate()
    [void][WentuyiDirectInsertSmokeNative]::SetForegroundWindow($Form.Handle)
    [void][WentuyiDirectInsertSmokeNative]::SetFocus($Box.Handle)
    $Box.Focus()
    $Form.TopMost = $false
    [System.Windows.Forms.Application]::DoEvents()
    Start-Sleep -Milliseconds 200
}

function Wait-Until([scriptblock] $Condition, [int] $TimeoutMs, [string] $Message) {
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ([DateTime]::UtcNow -lt $deadline) {
        [System.Windows.Forms.Application]::DoEvents()
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 100
    }
    throw $Message
}

function Assert-Style($Box, [int] $Offset, [System.Drawing.FontStyle] $Style, [System.Drawing.Color] $Color, [string] $Message) {
    $Box.Select($Offset, 1)
    $font = $Box.SelectionFont
    if (-not $font) { throw $Message }
    if (($font.Style -band $Style) -ne $Style) { throw $Message }
    if ($Box.SelectionColor.ToArgb() -ne $Color.ToArgb()) { throw $Message }
}

$oldPassphrase = $env:WENTUYI_PASSPHRASE
$form = $null
$script:exitCode = 1

try {
    Set-PortableJavaRuntime
    Set-DesktopCliRuntime
    $env:WENTUYI_PASSPHRASE = $Passphrase

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "Wentuyi Direct Insert Rich UI Test"
    $form.Width = 900
    $form.Height = 320
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen

    $box = New-Object System.Windows.Forms.RichTextBox
    $box.Dock = [System.Windows.Forms.DockStyle]::Fill
    $box.Multiline = $true
    $box.HideSelection = $false
    $box.Text = "prefix  suffix"
    $form.Controls.Add($box)

    $form.Add_Shown({
        try {
            Focus-Box $form $box
            $box.Select(0, 6)
            $box.SelectionFont = New-Object System.Drawing.Font($box.Font, [System.Drawing.FontStyle]::Bold)
            $box.SelectionColor = [System.Drawing.Color]::DarkGreen
            $suffixStart = $box.Text.IndexOf("suffix", [StringComparison]::Ordinal)
            $box.Select($suffixStart, 6)
            $box.SelectionFont = New-Object System.Drawing.Font($box.Font, [System.Drawing.FontStyle]::Italic)
            $box.SelectionColor = [System.Drawing.Color]::DarkBlue

            $box.Select("prefix ".Length, 0)
            Focus-Box $form $box
            & $InsertScript -EncryptText "direct rich" -TargetHwnd $box.Handle
            Wait-Until { $box.Text.Contains("WTY3:") } 14000 "direct encrypted insert did not appear: $($box.Text)"
            $encryptedText = $box.Text
            if (-not ($encryptedText.StartsWith("prefix WTY3:") -and $encryptedText.EndsWith(" suffix"))) {
                throw "unexpected direct encrypted text: $encryptedText"
            }
            Assert-Style $box 0 ([System.Drawing.FontStyle]::Bold) ([System.Drawing.Color]::DarkGreen) "prefix style lost after direct encrypt insert"
            Assert-Style $box ($encryptedText.Length - 2) ([System.Drawing.FontStyle]::Italic) ([System.Drawing.Color]::DarkBlue) "suffix style lost after direct encrypt insert"

            $payloadStart = $encryptedText.IndexOf("WTY3:", [StringComparison]::Ordinal)
            $payloadLength = $encryptedText.Length - "prefix ".Length - " suffix".Length
            $payload = $encryptedText.Substring($payloadStart, $payloadLength)
            $box.Select($payloadStart, $payloadLength)
            Focus-Box $form $box
            & $InsertScript -DecryptText $payload -TargetHwnd $box.Handle
            Wait-Until { $box.Text -eq "prefix direct rich suffix" } 14000 "direct decrypted insert did not replace selection: $($box.Text)"
            Assert-Style $box 0 ([System.Drawing.FontStyle]::Bold) ([System.Drawing.Color]::DarkGreen) "prefix style lost after direct decrypt insert"
            Assert-Style $box ($box.Text.Length - 2) ([System.Drawing.FontStyle]::Italic) ([System.Drawing.Color]::DarkBlue) "suffix style lost after direct decrypt insert"

            @(
                "windows-direct-rich-encrypted-prefix=WTY3:",
                "windows-direct-rich-decrypted=direct rich",
                "windows-direct-rich-tags=preserved"
            ) | Out-File -LiteralPath $OutPath -Encoding UTF8
            $script:exitCode = 0
        } catch {
            @(
                "error-record:",
                ($_ | Format-List * -Force | Out-String),
                "script-stack:",
                $_.ScriptStackTrace,
                "text:",
                $box.Text
            ) | Out-File -LiteralPath $OutPath -Encoding UTF8
            $script:exitCode = 1
        } finally {
            $form.Close()
        }
    })

    [System.Windows.Forms.Application]::Run($form)
    exit $script:exitCode
} finally {
    $env:WENTUYI_PASSPHRASE = $oldPassphrase
    if ($form) { $form.Dispose() }
}
