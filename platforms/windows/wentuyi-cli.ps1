# No param() block on purpose: a simple script puts positional args in $args and piped
# input in $input. An advanced param block with [ValueFromRemainingArguments] REJECTS
# pipeline input ("input object cannot be bound to any parameters" under $ErrorAction Stop),
# which silently broke the --stdin secret path — so collect both manually here. Forwarding
# $PipedInput to the child's stdin is what keeps plaintext off the command line.
$ErrorActionPreference = "Stop"
$PipedInput = @($input)   # piped stdin (text/payload), collected before anything consumes it
$CliArgs = $args          # the desktop-cli command + its arguments

# NOTE: we do NOT pipe to desktop-cli with `|`. desktop-cli reads stdin / writes stdout as
# UTF-8, but PowerShell's native pipe uses the console code page (PS 5.1: $OutputEncoding=ASCII,
# stdout=OEM 437/936) and turns non-ASCII plaintext (Chinese!) into "?" in BOTH directions —
# verified on real PS 5.1. Invoke-NativeUtf8 (below) instead writes raw UTF-8 bytes to the
# child's stdin and reads its stdout decoded as UTF-8, so CJK round-trips correctly.
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$RuntimeCandidates = @()
if ($env:WENTUYI_JAVA_HOME) { $RuntimeCandidates += $env:WENTUYI_JAVA_HOME }
$RuntimeCandidates += Join-Path $ScriptDir "jre"
$RuntimeCandidates += Join-Path $ScriptDir "runtime\jre"
$RuntimeCandidates += Join-Path $ScriptDir "..\jre"
foreach ($runtime in $RuntimeCandidates) {
    if ($runtime -and (Test-Path (Join-Path $runtime "bin\java.exe"))) {
        $env:JAVA_HOME = (Resolve-Path $runtime).Path
        $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
        break
    }
}

$Candidates = @()
if ($env:WENTUYI_CLI) { $Candidates += $env:WENTUYI_CLI }
$Candidates += Join-Path $RepoRoot "desktop-cli\build\install\desktop-cli\bin\desktop-cli.bat"
$Candidates += Join-Path $ScriptDir "desktop-cli\bin\desktop-cli.bat"
$Candidates += Join-Path $ScriptDir "..\desktop-cli\bin\desktop-cli.bat"

$Cli = $Candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $Cli -and (Test-Path (Join-Path $RepoRoot "gradlew.bat"))) {
    Push-Location $RepoRoot
    try { & .\gradlew.bat :desktop-cli:installDist | Out-Host }
    finally { Pop-Location }
    $Cli = Join-Path $RepoRoot "desktop-cli\build\install\desktop-cli\bin\desktop-cli.bat"
}

if (-not $Cli -or -not (Test-Path $Cli)) {
    throw "desktop-cli.bat not found. Set WENTUYI_CLI or run .\gradlew.bat :desktop-cli:installDist."
}

# Quote one argument for ProcessStartInfo.Arguments per Windows CommandLineToArgvW rules.
function Quote-Arg([string] $a) {
    if ($a.Length -gt 0 -and $a -notmatch '[\s"]') { return $a }
    $s = $a -replace '(\\*)"', '$1$1\"'   # double backslashes before a quote, then escape it
    $s = $s -replace '(\\+)$', '$1$1'     # double trailing backslashes (before the closing ")
    return '"' + $s + '"'
}

# Run a native child with UTF-8 stdin (raw bytes) and UTF-8 stdout, bypassing PowerShell's
# code-page pipe. Returns stdout text; sets $LASTEXITCODE.
function Invoke-NativeUtf8([string] $Exe, [string[]] $Argv, [string[]] $StdinLines) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = (($Argv | ForEach-Object { Quote-Arg $_ }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding $false
    $psi.StandardErrorEncoding  = New-Object System.Text.UTF8Encoding $false
    $hasStdin = $StdinLines -and $StdinLines.Count -gt 0
    if ($hasStdin) { $psi.RedirectStandardInput = $true }
    $p = [System.Diagnostics.Process]::Start($psi)
    if ($hasStdin) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($StdinLines -join "`n"))
        $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $p.StandardInput.BaseStream.Flush()
        $p.StandardInput.Close()
    }
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($err) { [Console]::Error.Write($err) }
    $global:LASTEXITCODE = $p.ExitCode
    return $out
}

if ($Cli.EndsWith(".bat", [System.StringComparison]::OrdinalIgnoreCase)) {
    $AppHome = Resolve-Path (Join-Path (Split-Path -Parent $Cli) "..")
    $JavaExe = "java.exe"
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $JavaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    }
    $jargv = @("-cp", (Join-Path $AppHome "lib\*"), "com.wentuyi.cli.WentuyiCliKt") + $CliArgs
    $result = Invoke-NativeUtf8 $JavaExe $jargv $PipedInput
} else {
    $result = Invoke-NativeUtf8 $Cli $CliArgs $PipedInput
}
if ($result) { Write-Output $result.TrimEnd([char]13, [char]10) }
exit $LASTEXITCODE
