<#
.SYNOPSIS
    Smoke test of cloud/dist/start.bat, start.ps1 and the windows archive (operations.yml).

.DESCRIPTION
    .github/scripts/smoke-start-windows.ps1 -Dist <folder with VectoraCloud.jar and the archives>
        -Java25 <Java 21+ home> -Java17 <Java home older than 21>

    GitHub's Windows runners run elevated, so a start without --allow-root must be refused there
    (exit 2). The script checks the elevation itself and expects 2 or 0 accordingly.
#>
param(
    [Parameter(Mandatory)] [string] $Dist,
    [Parameter(Mandatory)] [string] $Java25,
    [Parameter(Mandatory)] [string] $Java17
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).ProviderPath
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("vectora-smoke-" + [guid]::NewGuid().ToString('N'))
# A folder with a space, parentheses, an ampersand and an exclamation mark: quoting mistakes show up here.
$dir = Join-Path $work 'vectora home (a&b!)'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $Dist 'VectoraCloud.jar') -Destination $dir
Copy-Item -LiteralPath (Join-Path $repo 'VectoraCloud\dist\start.bat') -Destination $dir
Copy-Item -LiteralPath (Join-Path $repo 'VectoraCloud\dist\start.ps1') -Destination $dir
$bat = Join-Path $dir 'start.bat'
$ps1 = Join-Path $dir 'start.ps1'

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$elevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Output "Elevated: $elevated"

$script:failures = 0
$saved = @{}
foreach ($name in 'JAVA_HOME', 'PATH', 'VECTORA_XMX', 'VECTORA_JAR', 'VECTORA_HOME', 'VECTORA_ALLOW_ROOT') {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Restore-Environment {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name])
    }
}

# Starts a program with an exact command line (no PowerShell argument quoting in between) and
# returns its exit code and combined output.
function Invoke-Native {
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [Parameter(Mandatory)] [string] $Arguments
    )
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = $Arguments
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    $stderr = $process.StandardError.ReadToEndAsync()
    $stdout = $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    [pscustomobject]@{ Code = $process.ExitCode; Output = $stdout + $stderr.Result }
}

function Invoke-Case {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [int] $Code,
        [string] $Text = '',
        [hashtable] $Environment = @{},
        [Parameter(Mandatory)] [string] $Kind,
        [Parameter(Mandatory)] [string] $Arguments
    )
    Restore-Environment
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $Java25)
    foreach ($key in $Environment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $Environment[$key])
    }
    $cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    switch ($Kind) {
        # /s: cmd removes exactly the outer quotes, so the quoted script path survives.
        'bat' { $result = Invoke-Native -FilePath $cmd -Arguments "/d /s /c `"`"$bat`" $Arguments`"" }
        'ps1' { $result = Invoke-Native -FilePath $powershell -Arguments "-NoProfile -ExecutionPolicy Bypass -File `"$ps1`" $Arguments" }
        default { $result = Invoke-Native -FilePath $cmd -Arguments "/d /s /c `"`"$Kind`" $Arguments`"" }
    }
    Restore-Environment
    if ($result.Code -ne $Code) {
        Write-Output "::error::${Name}: exit $($result.Code), expected $Code"
        Write-Output $result.Output
        $script:failures++
        return
    }
    if ($Text -and -not $result.Output.Contains($Text)) {
        Write-Output "::error::${Name}: output lacks '$Text'"
        Write-Output $result.Output
        $script:failures++
        return
    }
    Write-Output "ok   $Name (exit $($result.Code))"
}

$refused = if ($elevated) { 2 } else { 0 }
$noJavaPath = Join-Path $env:SystemRoot 'System32'

$pwned = Join-Path $work 'pwned.txt'
foreach ($kind in 'bat', 'ps1') {
    Invoke-Case -Name "$kind version" -Code 0 -Text 'VectoraCloud' -Kind $kind -Arguments '--allow-root --version'
    Invoke-Case -Name "$kind help" -Code 0 -Text '--setup-file' -Kind $kind -Arguments '--allow-root --help'
    Invoke-Case -Name "$kind elevation check" -Code $refused -Kind $kind -Arguments '--lang en --version'
    Invoke-Case -Name "$kind VECTORA_ALLOW_ROOT" -Code 0 -Environment @{ VECTORA_ALLOW_ROOT = 'true' } -Kind $kind -Arguments '--version'
    Invoke-Case -Name "$kind unknown argument" -Code 64 -Text '--bogus' -Kind $kind -Arguments '--allow-root --lang en --bogus'
    Invoke-Case -Name "$kind java too old" -Code 3 -Text '21' -Environment @{ JAVA_HOME = $Java17 } -Kind $kind -Arguments '--allow-root --lang en --version'
    Invoke-Case -Name "$kind no java" -Code 3 -Text 'adoptium.net' -Environment @{ JAVA_HOME = $null; PATH = $noJavaPath } -Kind $kind -Arguments '--version'
    Invoke-Case -Name "$kind JAVA_HOME without java falls back to PATH" -Code 0 -Text 'from the PATH' `
        -Environment @{ JAVA_HOME = (Join-Path $work 'missing'); PATH = "$Java25\bin;$noJavaPath" } -Kind $kind -Arguments '--allow-root --version'
    Invoke-Case -Name "$kind VECTORA_XMX injection is not run" -Code 64 -Text 'VECTORA_XMX' `
        -Environment @{ VECTORA_XMX = "1G&echo INJECTED>`"$pwned`"" } -Kind $kind -Arguments '--version'
    Invoke-Case -Name "$kind JAVA_HOME injection is not run" -Code 0 -Text 'VectoraCloud' `
        -Environment @{ JAVA_HOME = "C:\nope (x86)&echo INJECTED>`"$pwned`""; PATH = "$Java25\bin;$noJavaPath" } -Kind $kind -Arguments '--allow-root --version'
    Invoke-Case -Name "$kind missing jar" -Code 66 -Text 'not found' -Environment @{ VECTORA_JAR = (Join-Path $work 'none.jar') } -Kind $kind -Arguments '--version'
    Invoke-Case -Name "$kind missing home" -Code 66 -Text 'cloud home' -Environment @{ VECTORA_HOME = (Join-Path $work 'none') } -Kind $kind -Arguments '--version'
}
if (Test-Path -LiteralPath $pwned) {
    Write-Output '::error::a value from the environment was run as a command'
    $script:failures++
}

# The release archive: CRLF scripts and the same jar.
$archive = Get-ChildItem -LiteralPath $Dist -Filter 'VectoraCloud-*-windows.zip' | Select-Object -First 1
if (-not $archive) {
    Write-Output "::error::no windows archive in $Dist"
    $script:failures++
} else {
    $unpacked = Join-Path $work 'archive'
    Expand-Archive -LiteralPath $archive.FullName -DestinationPath $unpacked
    $archiveBat = Get-ChildItem -LiteralPath $unpacked -Recurse -Filter 'start.bat' | Select-Object -First 1
    $content = [System.IO.File]::ReadAllText($archiveBat.FullName)
    if (($content -split "`n").Count -ne ($content -split "`r`n").Count) {
        Write-Output '::error::start.bat in the archive does not have CRLF line ends'
        $script:failures++
    }
    Invoke-Case -Name 'archive start.bat' -Code 0 -Text 'VectoraCloud' -Kind $archiveBat.FullName -Arguments '--allow-root --version'
}

Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
if ($script:failures -ne 0) {
    Write-Output "$($script:failures) case(s) failed"
    exit 1
}
Write-Output 'All start.bat and start.ps1 cases passed.'
