param(
    [string] $ZipPath = "$PSScriptRoot\wentuyi-desktop-cli.zip",
    [string] $WorkDir = "$env:TEMP\wentuyi-package-test",
    [string] $OutPath
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-WentuyiPackageSmoke {
    if (-not (Test-Path $ZipPath)) { throw "missing package zip: $ZipPath" }
    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $WorkDir -Force

    $jreZip = Join-Path $ScriptDir "jre-windows.zip"
    if (Test-Path $jreZip) {
        $jreRoot = Join-Path $WorkDir "jre"
        New-Item -ItemType Directory -Force -Path $jreRoot | Out-Null
        Expand-Archive -LiteralPath $jreZip -DestinationPath $jreRoot -Force
        $java = Get-ChildItem -Path $jreRoot -Filter java.exe -Recurse | Select-Object -First 1
        if (-not $java) { throw "jre-windows.zip did not contain java.exe" }
        $env:WENTUYI_JAVA_HOME = (Resolve-Path (Join-Path $java.DirectoryName ".." )).Path
        $env:JAVA_HOME = $env:WENTUYI_JAVA_HOME
        $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
    }

    $env:WENTUYI_CLI = Join-Path $WorkDir "desktop-cli\bin\desktop-cli.bat"
    if (-not (Test-Path $env:WENTUYI_CLI)) { throw "missing extracted CLI: $env:WENTUYI_CLI" }

    & (Join-Path $ScriptDir "test-local.ps1")
}

if ($OutPath) {
    $captured = @()
    try {
        $captured = @(Invoke-WentuyiPackageSmoke 2>&1)
        $captured | Out-File -LiteralPath $OutPath -Encoding UTF8
        exit 0
    } catch {
        @(
            $captured
            "error-record:"
            ($_ | Format-List * -Force | Out-String)
            "script-stack:"
            $_.ScriptStackTrace
        ) | Out-File -LiteralPath $OutPath -Encoding UTF8
        exit 1
    }
}

Invoke-WentuyiPackageSmoke
