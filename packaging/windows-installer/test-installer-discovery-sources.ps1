# Synthetic registry/shortcut discovery: no host installation, registry edits or COM.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
$script:Assertions = 0
function Assert-DiscoverySource {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Discovery source regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}

# Replace only OS inventory readers. Root extraction, validation, prioritization
# and deduplication run through the actual Get-CubismDiscoveryRoots entry point.
$script:RegistryEntries = @()
$script:ShortcutTargets = @{}
function Get-CubismRegistryEntries { return $script:RegistryEntries }
function Get-CubismDirectories { return @() }
function Get-CubismShallowDiscoveryRoots { return @() }
function Get-CubismShortcutFiles { return @($script:ShortcutTargets.Keys | Sort-Object) }
function Get-CubismShortcutTarget {
    param([string]$Path)
    if ($script:ShortcutTargets[$Path] -eq 'BROKEN') { throw 'Synthetic broken shortcut' }
    return $script:ShortcutTargets[$Path]
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('turboism-discovery-sources-' + [guid]::NewGuid().ToString('N'))
$previousRoot = $env:CUBISM_ROOT
$previousFixture = $env:TURBOISM_DISCOVERY_FIXTURE
try {
    $env:CUBISM_ROOT = ''
    $root = Join-Path $fixture 'custom/very/deep/用户 自定义目录/Live2D Cubism 5.3'
    foreach ($directory in @('app/lib', 'app/jre/bin', 'uninstall')) {
        [void][IO.Directory]::CreateDirectory((Join-Path $root $directory))
    }
    foreach ($file in @('CubismEditor5.bat', 'CubismEditor5.exe', 'app/lib/Live2D_Cubism.jar', 'app/jre/bin/java.exe', 'uninstall/unins000.exe')) {
        [IO.File]::WriteAllText((Join-Path $root $file), 'synthetic; never executed')
    }
    $canonical = ConvertTo-CubismCanonicalRoot $root
    foreach ($property in @('InstallLocation', 'InstallDir', 'Install_Dir', 'Location')) {
        # Missing optional properties must be safe even under StrictMode.
        $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism 5.3'; $property=$root })
        $found = @(Get-CubismDiscoveryRoots)
        Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) "registry $property finds an arbitrarily nested installation"
    }
    foreach ($property in @('UninstallString', 'QuietUninstallString')) {
        $uninstaller = Join-Path $root 'uninstall/unins000.exe'
        $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism 5.3'; $property=('"{0}" /S' -f $uninstaller) })
        $found = @(Get-CubismDiscoveryRoots)
        Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) "registry $property infers the host from a quoted uninstaller with arguments"
    }
    $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism'; UninstallString=((Join-Path $root 'uninstall/unins000.exe') + ' /S') })
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) 'unquoted uninstall paths with spaces are read without executing their commands'
    $env:TURBOISM_DISCOVERY_FIXTURE = $root
    $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism 5.3'; DisplayIcon='"%TURBOISM_DISCOVERY_FIXTURE%/CubismEditor5.exe",0' })
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) 'DisplayIcon expands environment variables and strips the icon index'
    $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism'; DisplayIcon=((Join-Path $root 'CubismEditor5.exe') + ',-12') })
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) 'unquoted DisplayIcon paths accept negative resource indices'

    $script:RegistryEntries = @(
        [pscustomobject]@{ DisplayName='Unrelated app'; InstallLocation=$root },
        [pscustomobject]@{ DisplayName='Live2D stale record'; InstallLocation=(Join-Path $fixture 'missing') },
        [pscustomobject]@{ DisplayName='Cubism bad record'; DisplayIcon='"unterminated' },
        [pscustomobject]@{ KeyName='Live2D Cubism'; InstallLocation=$root; DisplayIcon=(Join-Path $root 'CubismEditor5.exe') }
    )
    $found = Get-CubismDiscoveryRoots -SavedRoots @($root) -IncludeMetadata
    Assert-DiscoverySource ($found.Roots.Count -eq 1 -and $found.AutomaticRootKeys.Count -eq 1) 'partial, malformed and stale registry entries do not suppress a valid entry or duplicate a saved root'

    $script:RegistryEntries = @()
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 0) 'unregistered deep installation has no guessed path'
    $script:ShortcutTargets['a-broken.lnk'] = 'BROKEN'
    $script:ShortcutTargets['b-stale.lnk'] = Join-Path $fixture 'deleted/CubismEditor5.bat'
    $script:ShortcutTargets['c-renamed.lnk'] = Join-Path $root 'CubismEditor5.bat'
    $script:ShortcutTargets['d-other-name.lnk'] = Join-Path $root 'CubismEditor5.exe'
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 1 -and $found[0] -eq $canonical) 'renamed BAT and EXE shortcuts locate a deep installation and tolerate broken links'
    $script:RegistryEntries = @([pscustomobject]@{ DisplayName='Live2D Cubism'; InstallLocation=$root })
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 1) 'registry and shortcut discoveries are deduplicated'

    Remove-Item -LiteralPath (Join-Path $root 'app/lib/Live2D_Cubism.jar')
    $found = @(Get-CubismDiscoveryRoots)
    Assert-DiscoverySource ($found.Count -eq 0) 'registry and shortcuts cannot admit a directory without the required host files'
    Write-Output "INSTALLER_DISCOVERY_SOURCES_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    $env:CUBISM_ROOT = $previousRoot
    $env:TURBOISM_DISCOVERY_FIXTURE = $previousFixture
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
