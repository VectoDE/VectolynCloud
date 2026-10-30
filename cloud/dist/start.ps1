<#
.SYNOPSIS
    Starts VectoraCloud (Windows PowerShell 5.1 and PowerShell 7).

.DESCRIPTION
    .\start.ps1 [launcher arguments]     for example: .\start.ps1 --non-interactive --setup-file setup.yml

    Environment:
      VECTORA_HOME      cloud home and working directory (default: the folder of this script)
      VECTORA_JAR       path of VectoraCloud.jar (default: next to this script)
      VECTORA_XMX       maximum heap of the cloud itself, e.g. 512M or 1G (default: 512M)
      JAVA_HOME         Java installation to use; otherwise java from the PATH
      JDK_JAVA_OPTIONS  further JVM options, read by the java launcher itself (Java 9 and later)

    Every argument is passed to VectoraCloud unchanged (--allow-root, --setup-file, --lang, ...).
    The Java version (21 or later) is checked by VectoraCloud.jar itself. Exit codes: see
    docs/cloud/operations.md. Do not use "Run as administrator": VectoraCloud refuses an elevated
    start unless --allow-root is given.

    The script has no param() block on purpose: PowerShell would otherwise try to bind arguments
    such as --help itself. Everything in $args goes to Java as separate arguments; nothing is
    passed through Invoke-Expression or a command string.
#>

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

function Exit-WithMessage {
    param(
        [Parameter(Mandatory)] [int] $Code,
        [Parameter(Mandatory)] [string] $English,
        [Parameter(Mandatory)] [string] $German
    )
    [Console]::Error.WriteLine("VectoraCloud: $English")
    [Console]::Error.WriteLine("VectoraCloud: $German")
    exit $Code
}

# Absolute path of an existing file, or $null. Paths with characters a file name cannot have
# (quotes, < > |, ...) count as missing instead of stopping the script with an exception.
function Resolve-ExistingFile {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Path)
    try {
        $full = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine((Get-Location).ProviderPath, $Path))
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            return $full
        }
    } catch {
        Write-Verbose "Unusable path: $Path"
    }
    return $null
}

# Quotes around values in the environment (JAVA_HOME="C:\...") are common; they are removed, as in
# start.bat.
function Get-EnvironmentValue {
    param([Parameter(Mandatory)] [string] $Name)
    $value = [System.Environment]::GetEnvironmentVariable($Name)
    if ($value) {
        return $value -replace '"', ''
    }
    return $null
}

$scriptDir = $PSScriptRoot
$onWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$javaName = if ($onWindows) { 'java.exe' } else { 'java' }

# VectoraCloud.jar, made absolute before the working directory changes.
$jarSetting = Get-EnvironmentValue -Name 'VECTORA_JAR'
if (-not $jarSetting) {
    $jarSetting = Join-Path $scriptDir 'VectoraCloud.jar'
}
$jar = Resolve-ExistingFile -Path $jarSetting
if (-not $jar) {
    Exit-WithMessage -Code 66 -English "VectoraCloud.jar not found: $jarSetting" `
        -German "VectoraCloud.jar nicht gefunden: $jarSetting"
}

$xmx = Get-EnvironmentValue -Name 'VECTORA_XMX'
if (-not $xmx) {
    $xmx = '512M'
}
if ($xmx -notmatch '^[0-9]{1,7}[kKmMgG]?$') {
    Exit-WithMessage -Code 64 -English "VECTORA_XMX must be a size such as 512M or 2G, not: $xmx" `
        -German "VECTORA_XMX muss eine Groesse wie 512M oder 2G sein, nicht: $xmx"
}

# Java: JAVA_HOME first, then the PATH.
$java = $null
$javaHome = Get-EnvironmentValue -Name 'JAVA_HOME'
if ($javaHome) {
    $java = Resolve-ExistingFile -Path ($javaHome.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar + 'bin' + [System.IO.Path]::DirectorySeparatorChar + $javaName)
    if (-not $java) {
        [Console]::Error.WriteLine("VectoraCloud: JAVA_HOME ($javaHome) has no bin\$javaName, trying java from the PATH.")
        [Console]::Error.WriteLine("VectoraCloud: JAVA_HOME ($javaHome) enthaelt kein bin\$javaName, versuche java aus dem PATH.")
    }
}
if (-not $java) {
    $found = Get-Command -Name $javaName -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) {
        $java = $found.Source
    }
}
if (-not $java) {
    Exit-WithMessage -Code 3 `
        -English 'No Java found. Install Java 21 or later (e.g. https://adoptium.net/temurin/releases/) and put it on the PATH or set JAVA_HOME.' `
        -German 'Kein Java gefunden. Installiere Java 21 oder neuer (z. B. https://adoptium.net/de/temurin/releases/) und nimm es in den PATH auf oder setze JAVA_HOME.'
}

$cloudHome = Get-EnvironmentValue -Name 'VECTORA_HOME'
if (-not $cloudHome) {
    $cloudHome = $scriptDir
}
try {
    Set-Location -LiteralPath $cloudHome
    # Java resolves relative paths against the process working directory, not PowerShell's location.
    [System.Environment]::CurrentDirectory = (Get-Location).ProviderPath
} catch {
    Exit-WithMessage -Code 66 -English "Cannot change to the cloud home: $cloudHome" `
        -German "Kann nicht in das Cloud-Home wechseln: $cloudHome"
}

# UTF-8 for the console and for what Java writes to it (non-ASCII characters in the console output).
if ($onWindows) {
    try {
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        & chcp.com 65001 | Out-Null
    } catch {
        Write-Verbose 'Console encoding could not be switched to UTF-8.'
    }
}

$javaArgs = @("-Xmx$xmx", '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $jar) + $args
& $java @javaArgs
exit $LASTEXITCODE
