# Runs bridge logic without a desktop or clipboard, using the real PowerShell parser.
# Compatible with Windows PowerShell 5.1 and PowerShell 7. test-local.ps1 additionally
# covers the UTF-8 native-process wrapper against the real JVM CLI.
$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Work = Join-Path ([IO.Path]::GetTempPath()) ("wentuyi-bridges-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $Work | Out-Null
$OldPassphrase = $env:WENTUYI_PASSPHRASE
$OldAppData = $env:APPDATA
$Global:WentuyiBridgeCalls = @()
$FakeCli = Join-Path $Work "fake-cli.ps1"
@'
$received = @($input) -join "`n"
$global:WentuyiBridgeCalls += [pscustomobject]@{ ArgsList = @($args); Text = $received; Passphrase = $env:WENTUYI_PASSPHRASE }
$global:LASTEXITCODE = 0
if ($args[0] -eq "send") { Write-Output "WTY5:complete-payload" }
elseif ($args[0] -eq "payload-qr") { Write-Output "C:\qr\first.png"; Write-Output "C:\qr\second.png" }
elseif ($args[0] -eq "receive") { Write-Output $global:WentuyiBridgePlaintext }
else { throw "Unexpected CLI command: $($args[0])" }
'@ | Set-Content -LiteralPath $FakeCli -Encoding ASCII

function Read-BridgeAst([string] $Name) {
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $ScriptDir $Name), [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw ($errors | Out-String) }
    return $ast
}

function Get-BridgeFunctions($Ast, [string[]] $Names) {
    $definitions = $Ast.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $false) | Where-Object { -not $Names -or $_.Name -in $Names }
    return [scriptblock]::Create(($definitions | ForEach-Object { $_.Extent.Text }) -join "`n")
}

function Get-BridgeDispatch($Ast) {
    $start = $Ast.Find({ param($node)
        $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left.Extent.Text -eq '$set'
    }, $false)
    if (-not $start) { throw "No bridge dispatch found" }
    return [scriptblock]::Create($Ast.Extent.Text.Substring($start.Extent.StartOffset))
}

function Assert-Equal($Expected, $Actual, [string] $Label) {
    if ($Expected -cne $Actual) { throw "${Label}: expected <$Expected>, got <$Actual>" }
}

try {
    Remove-Item Env:\WENTUYI_PASSPHRASE -ErrorAction SilentlyContinue
    $env:APPDATA = $Work
    $PassphraseFile = Join-Path $Work "missing-passphrase"
    $CliScript = $FakeCli
    $Peer = "bob"
    $Text = $null
    $EncryptText = $null
    $DecryptText = $null
    $PlainImage = $null
    $EncryptedQr = $null
    $OutDir = $Work
    $App = "focused"
    # ASCII source is intentional: Windows PowerShell 5.1 reads no-BOM scripts as ANSI.
    $Message = " `t" + [char]0x4e2d + [char]0x6587 + "`r`nsecond line `t`n`n"
    $global:WentuyiBridgePlaintext = $Message

    $sendAst = Read-BridgeAst "wentuyi-send.ps1"
    . (Get-BridgeFunctions $sendAst)
    function Set-PortableJavaRuntime {}
    function Set-DesktopCliRuntime {}
    function Send-Body([string] $Body) { $global:WentuyiCapturedBody = $Body }
    function Send-Files([string[]] $Files) { $global:WentuyiCapturedFiles = $Files }
    $send = Get-BridgeDispatch $sendAst
    $EncryptText = $Message
    & $send
    Assert-Equal "WTY5:complete-payload" $global:WentuyiCapturedBody "single-line payload is complete"
    Assert-Equal $Message $global:WentuyiBridgeCalls[-1].Text "send stdin is exact"
    Assert-Equal "send,--peer,bob,--stdin" ($global:WentuyiBridgeCalls[-1].ArgsList -join ",") "send routes contact"

    $EncryptText = $null
    $EncryptedQr = $Message
    & $send
    Assert-Equal "WTY5:complete-payload" $global:WentuyiBridgeCalls[-1].Text "QR uses complete payload"
    Assert-Equal "C:\qr\first.png,C:\qr\second.png" ($global:WentuyiCapturedFiles -join ",") "multiple QR paths remain distinct"
    $EncryptedQr = $null

    # Write actual UTF-8 without a BOM, as PowerShell 7's installer does. The same
    # bytes must work in Windows PowerShell 5.1, whose default Get-Content uses ANSI.
    $PassphraseFile = Join-Path $Work "passphrase.txt"
    $FilePassphrase = "key-" + [char]0x4e2d + [char]0x6587
    $Utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [IO.File]::WriteAllText($PassphraseFile, $FilePassphrase, $Utf8NoBom)
    $EncryptText = $Message
    & $send
    Assert-Equal $FilePassphrase $global:WentuyiBridgeCalls[-1].Passphrase "send reads UTF-8 no-BOM key"
    Assert-Equal $null $env:WENTUYI_PASSPHRASE "send restores passphrase environment"
    $EncryptText = $null

    $hotkeyAst = Read-BridgeAst "wentuyi-hotkey.ps1"
    . (Get-BridgeFunctions $hotkeyAst @("Get-WentuyiPassphrase", "Invoke-WentuyiCli", "Convert-WentuyiText"))
    $payload = Convert-WentuyiText "encrypt" $Message
    Assert-Equal "WTY5:complete-payload" $payload "hotkey encrypt payload"
    Assert-Equal "send,--peer,bob,--stdin" ($global:WentuyiBridgeCalls[-1].ArgsList -join ",") "hotkey uses profile send"
    Assert-Equal $Message $global:WentuyiBridgeCalls[-1].Text "hotkey stdin is exact"
    Assert-Equal $FilePassphrase $global:WentuyiBridgeCalls[-1].Passphrase "hotkey reads UTF-8 no-BOM key"
    Assert-Equal $null $env:WENTUYI_PASSPHRASE "hotkey restores passphrase environment"
    Assert-Equal $Message (Convert-WentuyiText "decrypt" $payload) "hotkey decrypted whitespace"
    Assert-Equal "receive,--stdin" ($global:WentuyiBridgeCalls[-1].ArgsList -join ",") "hotkey auto-detects receive"

    # Load only logic functions; the UI native class is replaced with a recorder.
    Add-Type @'
public static class WentuyiBridgeRecorderNative {
    public static string LastText;
    public static void InsertText(System.IntPtr hwnd, string text) { LastText = text; }
}
'@
    $insertAst = Read-BridgeAst "wentuyi-insert.ps1"
    . (Get-BridgeFunctions $insertAst @("Get-WentuyiPassphrase", "Invoke-WentuyiCli"))
    $insertDispatch = Get-BridgeDispatch $insertAst
    $insert = [scriptblock]::Create($insertDispatch.ToString().Replace(
        "WentuyiDirectInsertNative", "WentuyiBridgeRecorderNative"))
    $TargetHwnd = [IntPtr]::Zero
    $DecryptText = "WTY5:complete-payload"
    & $insert
    Assert-Equal $Message ([WentuyiBridgeRecorderNative]::LastText) "direct insert decrypted whitespace"
    $DecryptText = $null
    $EncryptText = $Message
    & $insert
    Assert-Equal "WTY5:complete-payload" ([WentuyiBridgeRecorderNative]::LastText) "direct insert sends contact payload"
    Assert-Equal $Message $global:WentuyiBridgeCalls[-1].Text "direct insert stdin is exact"
    Assert-Equal $FilePassphrase $global:WentuyiBridgeCalls[-1].Passphrase "direct insert reads UTF-8 no-BOM key"
    Assert-Equal $null $env:WENTUYI_PASSPHRASE "direct insert restores passphrase environment"
    Write-Output "windows-bridge-regressions=passed"
} finally {
    if ($null -eq $OldPassphrase) { Remove-Item Env:\WENTUYI_PASSPHRASE -ErrorAction SilentlyContinue }
    else { $env:WENTUYI_PASSPHRASE = $OldPassphrase }
    if ($null -eq $OldAppData) { Remove-Item Env:\APPDATA -ErrorAction SilentlyContinue }
    else { $env:APPDATA = $OldAppData }
    Remove-Variable WentuyiBridgeCalls, WentuyiBridgePlaintext, WentuyiCapturedBody, WentuyiCapturedFiles -Scope Global -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Work -Recurse -Force
}
