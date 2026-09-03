param(
    [string] $OutPath = "C:\Temp\wentuyi\ui-rich-smoke.out",
    [string] $Passphrase = "rich-key"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$HotkeyScript = Join-Path $ScriptDir "wentuyi-hotkey.ps1"
$HotkeyStdout = Join-Path $ScriptDir "ui-rich-hotkey.stdout"
$HotkeyStderr = Join-Path $ScriptDir "ui-rich-hotkey.stderr"
$HotkeyLog = Join-Path $ScriptDir "ui-rich-hotkey.log"

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

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

Add-Type -ReferencedAssemblies @("System.Windows.Forms", "System.Drawing") -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

public static class WentuyiRichUiSmokeHarness {
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr SetFocus(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    private const int SW_RESTORE = 9;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const byte VK_CONTROL = 0x11;
    private const byte VK_MENU = 0x12;
    private const byte VK_C = 0x43;
    private const byte VK_D = 0x44;
    private const byte VK_E = 0x45;

    private static Form form;
    private static RichTextBox box;
    private static string outPath;
    private static string debugPath;
    private static string hotkeyLog;
    private static string hotkeyStderr;
    private static int exitCode = 1;
    private static bool workerStarted = false;

    public static int Run(string outputPath, string logPath, string stderrPath) {
        outPath = outputPath;
        debugPath = outputPath + ".debug";
        hotkeyLog = logPath;
        hotkeyStderr = stderrPath;
        exitCode = 1;
        workerStarted = false;
        Debug("run-start");

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        form = new Form();
        form.Text = "Wentuyi Rich UI Test";
        form.Width = 900;
        form.Height = 320;
        form.StartPosition = FormStartPosition.CenterScreen;

        box = new RichTextBox();
        box.Dock = DockStyle.Fill;
        box.Multiline = true;
        box.HideSelection = false;
        box.Text = "prefix windows rich suffix";
        form.Controls.Add(box);
        Debug("form-created");

        form.Shown += delegate {
            Debug("form-shown");
            StartWorkerOnce("shown");
        };

        System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        timer.Interval = 1500;
        timer.Tick += delegate {
            timer.Stop();
            Debug("timer-start");
            StartWorkerOnce("timer");
        };
        timer.Start();

        Application.Run(form);
        Debug("application-run-exit code=" + exitCode);
        return exitCode;
    }

    private static void StartWorkerOnce(string source) {
        if (workerStarted) {
            Debug("worker-already-started source=" + source);
            return;
        }
        workerStarted = true;
        Debug("worker-start source=" + source);
        Thread worker = new Thread(new ThreadStart(RunTest));
        worker.SetApartmentState(ApartmentState.STA);
        worker.Start();
    }

    private static void RunTest() {
        try {
            Debug("test-start");
            Ui(delegate {
                BringToFront();
                ApplyStyle(0, 6, FontStyle.Bold, Color.DarkGreen);
                int targetStart = box.Text.IndexOf("windows rich", StringComparison.Ordinal);
                ApplyStyle(targetStart, "windows rich".Length, FontStyle.Underline, Color.DarkRed);
                int suffixStart = box.Text.IndexOf("suffix", StringComparison.Ordinal);
                ApplyStyle(suffixStart, "suffix".Length, FontStyle.Italic, Color.DarkBlue);
            });
            Debug("styles-applied");

            Thread.Sleep(500);
            SelectTargetText("windows rich");
            Debug("target-selected-before-encrypt");

            SendRegisteredHotkey(VK_E);
            Debug("encrypt-hotkey-sent");
            WaitUntil(delegate { return Ui(delegate { return box.Text.Contains("WTY4:"); }); }, 14000, "encrypted text did not appear");
            string encryptedText = Ui(delegate { return box.Text; });
            Debug("encrypted-text=" + encryptedText);
            if (!encryptedText.StartsWith("prefix WTY4:", StringComparison.Ordinal) || !encryptedText.EndsWith(" suffix", StringComparison.Ordinal)) {
                throw new Exception("unexpected rich encrypted text: " + encryptedText);
            }
            AssertStyle(0, FontStyle.Bold, Color.DarkGreen, "prefix rich formatting was not preserved after encrypt");
            int encryptedPayloadStart = encryptedText.IndexOf("WTY4:", StringComparison.Ordinal);
            AssertStyle(encryptedPayloadStart, FontStyle.Underline, Color.DarkRed, "payload rich formatting was not preserved after encrypt");
            AssertStyle(encryptedText.Length - 2, FontStyle.Italic, Color.DarkBlue, "suffix rich formatting was not preserved after encrypt");

            Ui(delegate {
                BringToFront();
                int payloadLength = encryptedText.Length - "prefix ".Length - " suffix".Length;
                box.Select(encryptedPayloadStart, payloadLength);
                box.Focus();
            });
            Thread.Sleep(300);

            SendRegisteredHotkey(VK_D);
            Debug("decrypt-hotkey-sent");
            WaitUntil(delegate { return Ui(delegate { return box.Text == "prefix windows rich suffix"; }); }, 12000, "decrypted text did not appear");
            AssertStyle(0, FontStyle.Bold, Color.DarkGreen, "prefix rich formatting was not preserved after decrypt");
            AssertStyle("prefix ".Length, FontStyle.Underline, Color.DarkRed, "payload rich formatting was not preserved after decrypt");
            AssertStyle(Ui(delegate { return box.Text.Length; }) - 2, FontStyle.Italic, Color.DarkBlue, "suffix rich formatting was not preserved after decrypt");

            File.WriteAllLines(outPath, new string[] {
                "windows-rich-encrypted-prefix=WTY4:",
                "windows-rich-decrypted=windows rich",
                "windows-rich-tags=preserved"
            }, new UTF8Encoding(true));
            Debug("test-success");
            Complete(0);
        } catch (Exception ex) {
            Debug("test-error " + ex.ToString());
            WriteFailure(ex);
            Complete(1);
        }
    }

    private static void ApplyStyle(int start, int length, FontStyle style, Color color) {
        if (start < 0) { throw new Exception("style target not found"); }
        box.Select(start, length);
        box.SelectionFont = new Font(box.Font, style);
        box.SelectionColor = color;
    }

    private static void SelectTargetText(string text) {
        Ui(delegate {
            BringToFront();
            int start = box.Text.IndexOf(text, StringComparison.Ordinal);
            if (start < 0) { throw new Exception("target text not found: " + text); }
            box.Select(start, text.Length);
            box.Focus();
        });
        Thread.Sleep(300);
    }

    private static void BringToFront() {
        form.TopMost = true;
        ShowWindow(form.Handle, SW_RESTORE);
        form.Activate();
        SetForegroundWindow(form.Handle);
        SetFocus(box.Handle);
        box.Focus();
        form.TopMost = false;
    }

    private static void AssertSelectionCopies(string expected) {
        Clipboard.Clear();
        SendCtrlKey(VK_C);
        string copied = "";
        DateTime deadline = DateTime.UtcNow.AddMilliseconds(3000);
        while (DateTime.UtcNow < deadline) {
            copied = ClipboardText().TrimEnd(new char[] { (char)13, (char)10 });
            if (copied == expected) { return; }
            Thread.Sleep(100);
        }
        throw new Exception("selection copy probe failed: expected=[" + expected + "] actual=[" + copied + "]");
    }

    private static string ClipboardText() {
        try {
            if (!Clipboard.ContainsText(TextDataFormat.UnicodeText)) { return ""; }
            return Clipboard.GetText(TextDataFormat.UnicodeText);
        } catch {
            return "";
        }
    }

    private static void SendRegisteredHotkey(byte virtualKey) {
        keybd_event(VK_CONTROL, 0, 0, UIntPtr.Zero);
        keybd_event(VK_MENU, 0, 0, UIntPtr.Zero);
        keybd_event(virtualKey, 0, 0, UIntPtr.Zero);
        Thread.Sleep(80);
        keybd_event(virtualKey, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        Thread.Sleep(300);
    }

    private static void SendCtrlKey(byte virtualKey) {
        keybd_event(VK_CONTROL, 0, 0, UIntPtr.Zero);
        keybd_event(virtualKey, 0, 0, UIntPtr.Zero);
        Thread.Sleep(80);
        keybd_event(virtualKey, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        Thread.Sleep(220);
    }

    private static void AssertStyle(int offset, FontStyle style, Color color, string message) {
        bool ok = Ui(delegate {
            if (offset < 0 || offset >= box.TextLength) { return false; }
            box.Select(offset, 1);
            return box.SelectionFont != null
                && ((box.SelectionFont.Style & style) == style)
                && box.SelectionColor.ToArgb() == color.ToArgb();
        });
        if (!ok) { throw new Exception(message); }
    }

    private static void WaitUntil(Func<bool> condition, int timeoutMs, string timeoutMessage) {
        DateTime deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline) {
            if (condition()) { return; }
            Thread.Sleep(100);
        }
        throw new Exception(timeoutMessage);
    }

    private static void Ui(Action action) {
        if (form.InvokeRequired) {
            form.Invoke(action);
        } else {
            action();
        }
    }

    private static T Ui<T>(Func<T> func) {
        if (form.InvokeRequired) {
            return (T)form.Invoke(func);
        }
        return func();
    }

    private static void WriteFailure(Exception ex) {
        List<string> lines = new List<string>();
        lines.Add("error-record:");
        lines.Add(ex.ToString());
        lines.Add("hotkey-log:");
        lines.Add(SafeRead(hotkeyLog));
        lines.Add("hotkey-stderr:");
        lines.Add(SafeRead(hotkeyStderr));
        File.WriteAllLines(outPath, lines.ToArray(), new UTF8Encoding(true));
        Debug("failure-written");
    }

    private static string SafeRead(string path) {
        try {
            if (!File.Exists(path)) { return ""; }
            return File.ReadAllText(path);
        } catch (Exception readError) {
            return "unable to read " + path + ": " + readError.Message;
        }
    }

    private static void Debug(string message) {
        try {
            File.AppendAllText(debugPath, DateTime.Now.ToString("o") + " " + message + Environment.NewLine, Encoding.UTF8);
        } catch {
        }
    }

    private static void Complete(int code) {
        exitCode = code;
        try {
            if (form != null && form.IsHandleCreated) {
                form.BeginInvoke(new Action(delegate { form.Close(); }));
            }
        } catch {
        }
    }
}
"@

$oldPassphrase = $env:WENTUYI_PASSPHRASE
$hotkeyProcess = $null

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
    if ($hotkeyProcess.HasExited) { throw "hotkey process exited early ($($hotkeyProcess.ExitCode))" }

    $exitCode = [WentuyiRichUiSmokeHarness]::Run($OutPath, $HotkeyLog, $HotkeyStderr)
    exit $exitCode
} catch {
    @(
        "error-record:"
        ($_ | Format-List * -Force | Out-String)
        "script-stack:"
        $_.ScriptStackTrace
        "hotkey-log:"
        $(if (Test-Path $HotkeyLog) { Get-Content -LiteralPath $HotkeyLog -Raw } else { "" })
        "hotkey-stderr:"
        $(if (Test-Path $HotkeyStderr) { Get-Content -LiteralPath $HotkeyStderr -Raw } else { "" })
    ) | Out-File -LiteralPath $OutPath -Encoding UTF8
    exit 1
} finally {
    $env:WENTUYI_PASSPHRASE = $oldPassphrase
    Remove-Item Env:WENTUYI_HOTKEY_LOG -ErrorAction SilentlyContinue
    if ($hotkeyProcess -and -not $hotkeyProcess.HasExited) { $hotkeyProcess.Kill() }
}
