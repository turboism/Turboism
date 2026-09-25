# Offline synthetic tests for the real optional-engine installation path.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$helper = Join-Path $PSScriptRoot 'install-script-engine.ps1'
if (-not (Test-Path -LiteralPath $helper)) { throw 'Optional script engine installer is missing' }
. $helper
$script:Assertions = 0
function Assert-Engine {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Script engine regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}
function Assert-EngineThrows {
    param([scriptblock]$Action, [string]$Message)
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert-Engine $failed $Message
}
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('turboism-engine-regression-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($fixture)
try {
    $homePath = Join-Path $fixture 'Turboism 中文 home'
    $source = Join-Path $fixture 'offline jars'
    [void][System.IO.Directory]::CreateDirectory($homePath)
    [void][System.IO.Directory]::CreateDirectory($source)
    $manifest = Join-Path $homePath 'script-engine.json'
    $document = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'script-engine.json')) | ConvertFrom-Json
    foreach ($entry in $document.artifacts) {
        $file = Join-Path $source $entry.name
        [System.IO.File]::WriteAllText($file, ('synthetic jar bytes ' + $entry.name))
        $entry.bytes = [int64](Get-Item -LiteralPath $file).Length
        $entry.sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $originalManifest = $document | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($manifest, $originalManifest, $utf8)
    Assert-Engine ((Get-TurboismScriptEngineStatus -HomePath $homePath).Status -eq 'Absent') 'new thin install reports optional engine absent'
    . (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
    [void][System.IO.Directory]::CreateDirectory((Join-Path $homePath 'graal/lib'))
    [System.IO.File]::WriteAllText((Join-Path $homePath 'graal/lib/graal-host-0.44.0.jar'), 'synthetic base jar')
    $java = Join-Path $fixture 'fake-java.exe'
    [System.IO.File]::WriteAllText($java, 'synthetic Java, never executed')
    $absentHost = Resolve-TurboismGraalHost -TurboismHome $homePath -PreferredJava $java -WarningAction SilentlyContinue
    Assert-Engine ($null -eq $absentHost) 'thin package launches without claiming the script host is ready'
    Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source
    Assert-Engine ((Get-TurboismScriptEngineStatus -HomePath $homePath).Status -eq 'Ready') 'offline dependencies verify before becoming ready'
    foreach ($entry in $document.artifacts) {
        $installed = Join-Path (Join-Path $homePath 'graal/lib') $entry.name
        Assert-Engine ((Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash -ieq $entry.sha256) ('exact installed SHA-256: ' + $entry.name)
    }
    $first = Join-Path (Join-Path $homePath 'graal/lib') $document.artifacts[0].name
    $second = Join-Path (Join-Path $homePath 'graal/lib') $document.artifacts[1].name
    $stamp = [System.IO.File]::GetLastWriteTimeUtc($first)
    Install-TurboismScriptEngine -HomePath $homePath -OfflineSource (Join-Path $fixture 'missing-source')
    Assert-Engine ([System.IO.File]::GetLastWriteTimeUtc($first) -eq $stamp) 'unchanged installation skips both source and network'
    [System.IO.File]::WriteAllText($second, 'damaged')
    Assert-Engine ((Get-TurboismScriptEngineStatus -HomePath $homePath).Status -eq 'Invalid') 'corrupted optional engine is never reported ready'
    $invalidHost = Resolve-TurboismGraalHost -TurboismHome $homePath -PreferredJava $java -WarningAction SilentlyContinue
    Assert-Engine ($null -eq $invalidHost) 'corrupted engine disables scripting without blocking basic launch'
    Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source
    Assert-Engine ((Get-TurboismScriptEngineStatus -HomePath $homePath).Status -eq 'Ready') 'explicit reinstall repairs corrupted engine from verified source'
    Remove-Item -LiteralPath $first, $second
    [System.IO.File]::WriteAllText((Join-Path $source $document.artifacts[1].name), 'tampered offline download')
    Assert-EngineThrows { Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source } 'tampered dependency is rejected before publication'
    Assert-Engine (-not (Test-Path -LiteralPath $first) -and -not (Test-Path -LiteralPath $second)) 'bad second dependency does not publish the first'
    Assert-Engine (@(Get-ChildItem -LiteralPath (Join-Path $homePath 'graal') -Directory -Filter '.script-engine-*').Count -eq 0) 'failed staging is cleaned without touching other files'
    $document.artifacts[0].name = '../escape.jar'
    [System.IO.File]::WriteAllText($manifest, ($document | ConvertTo-Json -Depth 6), $utf8)
    Assert-EngineThrows { Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source } 'manifest cannot authorize path traversal or arbitrary artifact names'
    $document = $originalManifest | ConvertFrom-Json
    $document.artifacts[1].name = $document.artifacts[0].name
    [System.IO.File]::WriteAllText($manifest, ($document | ConvertTo-Json -Depth 6), $utf8)
    Assert-EngineThrows { Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source } 'duplicate dependency names are rejected'
    $document = $originalManifest | ConvertFrom-Json
    $document.artifacts[0].bytes = 1073741824
    [System.IO.File]::WriteAllText($manifest, ($document | ConvertTo-Json -Depth 6), $utf8)
    Assert-EngineThrows { Install-TurboismScriptEngine -HomePath $homePath -OfflineSource $source } 'oversized dependencies exceed the bounded download budget'
    [System.IO.File]::WriteAllText($manifest, $originalManifest, $utf8)
    [System.IO.File]::WriteAllBytes((Join-Path $source $document.artifacts[1].name), [byte[]]@(1,2,3))
    Assert-EngineThrows { Install-TurboismScriptEngine -HomePath $homePath -OfflineSource (Join-Path $fixture 'not-there') } 'offline source failure never falls back to network'
    Write-Output "SCRIPT_ENGINE_INSTALL_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
