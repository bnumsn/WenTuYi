$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Cli = Join-Path $ScriptDir "wentuyi-cli.ps1"

$payload = & $Cli encrypt-text --passphrase test-key windows hello
$plain = & $Cli decrypt-text --passphrase test-key $payload
if ($plain -ne "windows hello") { throw "passphrase round trip failed: $plain" }

function Get-CliField([string] $Text, [string] $Prefix) {
    $line = ($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -First 1)
    if (-not $line) { throw "missing CLI field: $Prefix" }
    return $line.Substring($Prefix.Length)
}

$alice = (& $Cli gen-identity --name windows-a) -join "`n"
$bob = (& $Cli gen-identity --name windows-b) -join "`n"
$aliceBackup = Get-CliField $alice "backup="
$bobBackup = Get-CliField $bob "backup="
$aliceQr = Get-CliField $alice "identityQr="
$bobQr = Get-CliField $bob "identityQr="
$sessionPayload = & $Cli session-encrypt --backup $aliceBackup --peer-qr $bobQr "windows session"
$sessionPlain = & $Cli session-decrypt --backup $bobBackup --peer-qr $aliceQr $sessionPayload
if ($sessionPlain -ne "windows session") { throw "session round trip failed: $sessionPlain" }
# One pipeline object goes through raw UTF-8 stdin; preserve spaces, CRLF and final LF.
$boundaryText = " `t" + [char]0x4e2d + [char]0x6587 + "`r`nsecond line `t`n`n"
$oldPassphrase = $env:WENTUYI_PASSPHRASE
try {
    $env:WENTUYI_PASSPHRASE = "windows-boundary-test"
    $boundaryPayload = $boundaryText | & $Cli send --stdin
    $boundaryPlain = $boundaryPayload | & $Cli receive --stdin
    if ($boundaryPlain -cne $boundaryText) { throw "UTF-8/whitespace round trip failed" }
} finally {
    $env:WENTUYI_PASSPHRASE = $oldPassphrase
}
& (Join-Path $ScriptDir "test-bridges.ps1")
$hotkey = & (Join-Path $ScriptDir "wentuyi-hotkey.ps1") -SelfTest
$directInsert = & (Join-Path $ScriptDir "wentuyi-insert.ps1") -SelfTest

Write-Output "windows-ok=$plain"
Write-Output "windows-session-ok=$sessionPlain"
Write-Output $hotkey
Write-Output $directInsert
