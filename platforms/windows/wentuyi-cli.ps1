param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $CliArgs
)

$ErrorActionPreference = "Stop"
# Collect any pipeline input up front so we can forward it to the child process' stdin
# (PowerShell does NOT auto-forward a wrapper script's pipeline input to a native child).
# This is what makes `--stdin` work for the bridges, keeping plaintext off the command line.
$PipedInput = @($input)
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

if ($Cli.EndsWith(".bat", [System.StringComparison]::OrdinalIgnoreCase)) {
    $AppHome = Resolve-Path (Join-Path (Split-Path -Parent $Cli) "..")
    $JavaExe = "java.exe"
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $JavaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    }
    if ($PipedInput.Count -gt 0) {
        $PipedInput | & $JavaExe -cp (Join-Path $AppHome "lib\*") com.wentuyi.cli.WentuyiCliKt @CliArgs
    } else {
        & $JavaExe -cp (Join-Path $AppHome "lib\*") com.wentuyi.cli.WentuyiCliKt @CliArgs
    }
    exit $LASTEXITCODE
}

if ($PipedInput.Count -gt 0) {
    $PipedInput | & $Cli @CliArgs
} else {
    & $Cli @CliArgs
}
exit $LASTEXITCODE
