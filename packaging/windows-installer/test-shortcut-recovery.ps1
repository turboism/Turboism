# Offline upgrade regression for generated shortcuts, including older untracked outputs.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
Set-StrictMode -Version 3.0
$nativeStage = ${function:New-CubismManagedShortcutStaged}
$nativeWriteState = ${function:Write-CubismInstallationState}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('turboism-shortcut-recovery-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
$script:shortcutDirectory = Join-Path $fixture 'shortcuts'
[void][IO.Directory]::CreateDirectory($script:shortcutDirectory)
# Replace only the Windows shell I/O boundary; configuration and state I/O are real.
function Get-CubismShortcutDirectory {
    param([string]$Override = '')
    if ($Override) { return $Override }
    return $script:shortcutDirectory
}
function Get-CubismShortcutProperties {
    param([string]$Path)
    return ([IO.File]::ReadAllText($Path) | ConvertFrom-Json)
}
function New-CubismManagedShortcutStaged {
    param([string]$TurboismHome, [object]$Candidate, [string]$Variant, [string]$Path)
    $temporary = Join-Path (Split-Path -Parent $Path) ('.turboism-' + [guid]::NewGuid().ToString('N') + '.lnk')
    $properties = [ordered]@{
        TargetPath = 'powershell.exe'
        Arguments = "launch $TurboismHome $($Candidate.CanonicalRoot) $Variant"
        WorkingDirectory = $TurboismHome
        Description = "Turboism managed Cubism $($Candidate.Version) launch"
    }
    [IO.File]::WriteAllText($temporary, ($properties | ConvertTo-Json))
    return [pscustomobject]@{ Temporary = $temporary; Hash = Get-CubismSha256 $temporary }
}
function Assert-Recovery {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    Write-Output "PASS $Message"
}
try {
    $statePath = Join-Path $fixture 'cubism-installations.json'
    $candidate = [pscustomobject]@{
        CanonicalRoot = (Join-Path $fixture 'Cubism5'); Version = '5.0.01'
        Selected = $true; Selectable = $true; D3DBat = ''
    }
    $path = New-CubismManagedShortcut -TurboismHome $fixture -Candidate $candidate
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    $state = Read-CubismInstallationState $statePath
    Assert-Recovery ($state.Valid -and $state.ManagedShortcuts -contains $path -and $state.ManagedShortcutHashes.Count -eq 1) 'lost-state shortcut is recovered with its ownership hash'
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    Assert-Recovery ($result.ManagedShortcuts.Count -eq 1) 'repeated enable succeeds'

    # A new release may change every part of the generated launch definition.
    # Older versions may also have emitted no ownership record at all.
    Remove-Item -LiteralPath $statePath -Force
    $oldProperties = [ordered]@{
        TargetPath = 'old-launcher.exe'; Arguments = 'old release arguments'
        WorkingDirectory = 'old installation'; Description = 'old release description'
    }
    [IO.File]::WriteAllText($path, ($oldProperties | ConvertTo-Json))
    $oldHash = Get-CubismSha256 $path
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    $properties = Get-CubismShortcutProperties $path
    Assert-Recovery ((Get-CubismSha256 $path) -ne $oldHash -and $properties.TargetPath -eq 'powershell.exe') 'upgrade refreshes old shortcut contents without ownership records'
    $state = Read-CubismInstallationState $statePath
    Assert-Recovery ($state.Valid -and $state.ManagedShortcuts -contains $path -and $state.ManagedShortcutHashes[0].Sha256 -eq (Get-CubismSha256 $path)) 'upgrade persists refreshed shortcut authority and hash'

    # An ordinary tracked upgrade also refreshes an older launch definition.
    [IO.File]::WriteAllText($path, ($oldProperties | ConvertTo-Json))
    Write-CubismInstallationState -StatePath $statePath -Candidates @($candidate) -ManagedShortcuts @($path) -ManagedShortcutHashes @([pscustomobject]@{ Path = $path; Sha256 = Get-CubismSha256 $path })
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    Assert-Recovery ((Get-CubismShortcutProperties $path).TargetPath -eq 'powershell.exe') 'tracked upgrade refreshes the old launch definition'

    # Shell metadata or older installer writes can invalidate the saved file hash.
    [IO.File]::WriteAllText($path, ($oldProperties | ConvertTo-Json))
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    Assert-Recovery ((Get-CubismShortcutProperties $path).TargetPath -eq 'powershell.exe') 'generated shortcut refresh tolerates a stale saved hash'

    # Unrelated shortcuts remain outside the selected generated destinations.
    $unrelated = Join-Path $script:shortcutDirectory 'My Cubism.lnk'
    [IO.File]::WriteAllText($unrelated, 'user shortcut')
    $outside = Join-Path $fixture (Split-Path -Leaf $path)
    [IO.File]::WriteAllText($outside, 'outside shortcut')
    Remove-Item -LiteralPath $statePath -Force
    $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
    Assert-Recovery (([IO.File]::ReadAllText($unrelated)) -eq 'user shortcut' -and ([IO.File]::ReadAllText($outside)) -eq 'outside shortcut') 'upgrade preserves unrelated and outside shortcuts'
    Remove-Item -LiteralPath $statePath -Force
    # State publication must precede replacing/deleting the recovered shortcut.
    $before = Get-CubismSha256 $path
    function Write-CubismInstallationState { throw 'injected state write failure' }
    $caught = $false
    try { Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate) | Out-Null }
    catch { $caught = $_.Exception.Message -eq 'injected state write failure' }
    Assert-Recovery ($caught -and (Get-CubismSha256 $path) -eq $before) 'state write failure preserves the orphan shortcut'
    ${function:Write-CubismInstallationState} = $nativeWriteState

    # A generated destination must still be a regular file, never a directory.
    Remove-Item -LiteralPath $path -Force
    [void][IO.Directory]::CreateDirectory($path)
    $caught = $false
    try { Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate) | Out-Null }
    catch { $caught = $_.Exception.Message -like '*not a regular generated file*' }
    Assert-Recovery ($caught -and (Test-Path -LiteralPath $path -PathType Container)) 'upgrade preserves a directory colliding with the generated filename'
    Remove-Item -LiteralPath $path -Force
    $path = New-CubismManagedShortcut -TurboismHome $fixture -Candidate $candidate

    # Native Windows CI also exercises actual .lnk parsing and publication.
    if ($env:OS -eq 'Windows_NT') {
        ${function:New-CubismManagedShortcutStaged} = $nativeStage
        Remove-Item -LiteralPath $path -Force
        $path = New-CubismManagedShortcut -TurboismHome $fixture -Candidate $candidate
        # Simulate a shortcut emitted by an older release using real shell COM.
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $null
        try {
            $shortcut = $shell.CreateShortcut($path)
            $shortcut.Arguments = '-old-release'
            $shortcut.Description = 'Old Turboism release'
            $shortcut.Save()
        }
        finally {
            if ($null -ne $shortcut) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shortcut) }
            [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell)
        }
        $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
        $state = Read-CubismInstallationState $statePath
        Assert-Recovery ($state.Valid -and $state.ManagedShortcuts -contains $path) 'real old COM shortcut is upgraded after ownership loss'
        $result = Invoke-CubismLaunchConfiguration -TurboismHome $fixture -StatePath $statePath -Candidates @($candidate)
        Assert-Recovery ($result.ManagedShortcuts.Count -eq 1) 'real COM shortcut can be enabled repeatedly'
    }
    Assert-Recovery (@(Get-ChildItem -LiteralPath $script:shortcutDirectory -Filter '.turboism-*').Count -eq 0) 'recovery leaves no staged shortcuts'
}
finally { Remove-Item -LiteralPath $fixture -Recurse -Force }
