# Synthetic production-seam gate for the Windows managed launcher.
# It creates only temporary Cubism-shaped fixtures; it never discovers or starts
# an installed Cubism host.
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")
Set-StrictMode -Version 3.0

function Assert-ManagedLaunch {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    Write-Host "ok: $Message"
}

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-managed-launch-" + [guid]::NewGuid().ToString("N"))
$turboismHome = Join-Path $temp "Turboism home 空间"
$fixtureBase = Join-Path $temp "fixtures"
$marker = Join-Path $temp "official bat marker.txt"
New-Item -ItemType Directory -Path $turboismHome, $fixtureBase -Force | Out-Null

function New-SyntheticCubism {
    param(
        [string]$Name,
        [string]$Version,
        [bool]$D3D = $false,
        [string]$Parent = "",
        [string]$LeafName = ""
    )
    $base = if ([string]::IsNullOrWhiteSpace($Parent)) { $fixtureBase } else { $Parent }
    $leaf = if ([string]::IsNullOrWhiteSpace($LeafName)) { "$Name Cubism $Version" } else { $LeafName }
    $root = Join-Path $base $leaf
    New-Item -ItemType Directory -Path (Join-Path $root "app\jre\bin"), (Join-Path $root "app\lib") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $root "CubismEditor5.bat") -Encoding ASCII -Value @(
        "@echo off",
        '>>"%TURBOISM_TEST_OUTPUT%" echo NORMAL=1',
        '>>"%TURBOISM_TEST_OUTPUT%" echo JDK=%JDK_JAVA_OPTIONS%',
        '>>"%TURBOISM_TEST_OUTPUT%" echo TOOL=%JAVA_TOOL_OPTIONS%',
        '>>"%TURBOISM_TEST_OUTPUT%" echo ARG1=%1',
        'exit /b 23'
    )
    [System.IO.File]::WriteAllBytes((Join-Path $root "app\jre\bin\java.exe"), [byte[]](1, 2, 3, 4))
    [System.IO.File]::WriteAllBytes((Join-Path $root "app\lib\Live2D_Cubism.jar"), [byte[]](5, 6, 7, 8))
    if ($D3D) {
        Set-Content -LiteralPath (Join-Path $root "CubismEditor5_D3D.bat") -Encoding ASCII -Value @(
            "@echo off",
            '>>"%TURBOISM_TEST_OUTPUT%" echo D3D=1',
            '>>"%TURBOISM_TEST_OUTPUT%" echo JDK=%JDK_JAVA_OPTIONS%',
            'exit /b 23'
        )
    }
    return $root
}

try {
    [System.IO.File]::WriteAllBytes((Join-Path $turboismHome "turboism-agent.jar"), [byte[]](9, 8, 7, 6))
    $root52 = New-SyntheticCubism -Name "Live2D" -Version "5.2"
    $root53 = New-SyntheticCubism -Name "Live2D" -Version "5.3" -D3D $true
    $root53DuplicateVersion = New-SyntheticCubism -Name "Live2D" -Version "5.3.02"
    $newSupported = New-SyntheticCubism -Name "Live2D" -Version "5.3.01"
    $unsupported = New-SyntheticCubism -Name "fixture unsupported" -Version "5.4.00"

    $scanDrive = Join-Path $temp "D-shaped-drive"
    $scanProgramFiles = Join-Path $scanDrive "Program Files"
    New-Item -ItemType Directory -Path $scanProgramFiles, (Join-Path $scanProgramFiles "Live2D") -Force | Out-Null
    foreach ($index in 1..96) {
        New-Item -ItemType Directory -Path (Join-Path $scanProgramFiles ("unrelated-{0:D3}" -f $index)) -Force | Out-Null
    }
    $directScanRoot = New-SyntheticCubism -Name "Live2D" -Version "5.3" -Parent $scanProgramFiles
    $nestedScanRoot = New-SyntheticCubism -Name "placeholder" -Version "5.2" -Parent (Join-Path $scanProgramFiles "Live2D") -LeafName "Cubism 5.2"
    $shallowRoots = @(Get-CubismShallowDiscoveryRoots -DriveRoots @($scanDrive))
    Assert-ManagedLaunch (@($shallowRoots | Where-Object { $_ -ieq (ConvertTo-CubismCanonicalRoot $directScanRoot) }).Count -eq 1) "fixed-drive traversal finds direct Live2D Cubism 5.3 in a synthetic drive-shaped tree"
    Assert-ManagedLaunch (@($shallowRoots | Where-Object { $_ -ieq (ConvertTo-CubismCanonicalRoot $nestedScanRoot) }).Count -eq 1) "fixed-drive traversal finds nested Live2D/Cubism 5.2"
    Assert-ManagedLaunch (@($shallowRoots | Where-Object { $_ -match '(?i)unrelated-' }).Count -eq 0) "unrelated directory names do not consume candidate results"
    $reparseScanRoot = Join-Path $scanProgramFiles "Live2D Cubism 5.2"
    $reparseCreated = $false
    try {
        New-Item -ItemType SymbolicLink -Path $reparseScanRoot -Target $nestedScanRoot -ErrorAction Stop | Out-Null
        $reparseCreated = $true
        $reparseRoots = @(Get-CubismShallowDiscoveryRoots -DriveRoots @($scanDrive))
        Assert-ManagedLaunch (@($reparseRoots | Where-Object { $_ -ieq (ConvertTo-CubismCanonicalRoot $reparseScanRoot) }).Count -eq 0) "reparse-point discovery entries are ignored"
    }
    catch { Write-Host "ok: reparse-point fixture unavailable on this Windows host" }
    finally {
        if ($reparseCreated) { Remove-Item -LiteralPath $reparseScanRoot -Force -ErrorAction SilentlyContinue }
    }

    # Keep the fixture hermetic: candidate inventory receives only synthetic roots.
    $roots = @($root53, $root52, $root53.ToUpperInvariant(), $root53DuplicateVersion)
    $candidates = Get-CubismInstallations -Roots $roots
    Assert-ManagedLaunch (@($candidates).Count -eq 3) "case-insensitive root dedupe keeps two 5.3 family installs"
    Assert-ManagedLaunch (@($candidates | Where-Object { $_.Selectable }).Count -eq 3) "synthetic unsuffixed 5.2 and 5.3 family roots pass file-shape checks"
    Assert-ManagedLaunch ($candidates[0].Version -eq "5.2" -and $candidates[1].Version -eq "5.3") "inventory order is family version then canonical path"
    Assert-ManagedLaunch (($candidates | Where-Object { $_.D3DBat }).Count -eq 1) "D3D BAT is an optional separately named entry"

    $initial = Merge-CubismSelection -Candidates $candidates -SavedInstallations @()
    Assert-ManagedLaunch (@($initial | Where-Object { -not $_.Selected }).Count -eq 0) "initial supported inventory is all-selected"
    $initial[1].Selected = $false
    Write-CubismInstallationState -StatePath (Join-Path $turboismHome "cubism-installations.json") -Candidates $initial
    $statePath = Join-Path $turboismHome "cubism-installations.json"
    $saved = Read-CubismInstallationState -StatePath $statePath
    Assert-ManagedLaunch ($saved.Valid -and @($saved.Installations).Count -eq 3) "bounded state round-trips three installation entries"
    Assert-ManagedLaunch (@($saved.Installations | Where-Object { $_.Root -eq (ConvertTo-CubismCanonicalRoot $root53) }).Count -eq 1) "family installation root round-trips through state"
    Set-Content -LiteralPath $statePath -Value '{"format":"turboism.cubism.installation-state","schemaVersion":1,"installations":[]' -Encoding ASCII
    $malformedState = Read-CubismInstallationState -StatePath $statePath
    Assert-ManagedLaunch (-not $malformedState.Valid -and @($malformedState.ManagedShortcuts).Count -eq 0) "malformed state fails closed without shortcut authority"
    [System.IO.File]::WriteAllBytes($statePath, [byte[]](New-Object byte[] 65537))
    $oversizedState = Read-CubismInstallationState -StatePath $statePath
    Assert-ManagedLaunch (-not $oversizedState.Valid) "oversized state is rejected before parsing"
    Write-CubismInstallationState -StatePath $statePath -Candidates $initial
    $beforeWriteCap = (Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash
    $writeCapCandidates = @(0..255 | ForEach-Object {
        [pscustomobject]@{ CanonicalRoot = "C:\Turboism-write-cap-$($_)-$('x' * 220)"; Version = "5.3"; Selected = $true }
    })
    $writeCapThrew = $false
    try { Write-CubismInstallationState -StatePath $statePath -Candidates $writeCapCandidates } catch { $writeCapThrew = $true }
    Assert-ManagedLaunch ($writeCapThrew) "state writer rejects the UTF-8 byte cap"
    Assert-ManagedLaunch ((Get-FileHash -LiteralPath $statePath -Algorithm SHA256).Hash -eq $beforeWriteCap) "failed state write preserves prior ownership state"
    $saved = Read-CubismInstallationState -StatePath $statePath
    $savedRoots = @($saved.Installations | ForEach-Object { $_.Root })
    $withNewRoots = @($savedRoots + $root52 + $root53 + $root53DuplicateVersion + $newSupported + $unsupported)
    $withNew = Get-CubismInstallations -Roots $withNewRoots
    $merged = Merge-CubismSelection -Candidates $withNew -SavedInstallations $saved.Installations
    Assert-ManagedLaunch (@($merged | Where-Object { $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $root53) -and $_.Selected }).Count -eq 0) "saved deselection is preserved"
    Assert-ManagedLaunch (@($merged | Where-Object { $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $newSupported) -and $_.Selected }).Count -eq 1) "truly new supported candidate defaults selected"
    Assert-ManagedLaunch (@($merged | Where-Object { $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $unsupported) -and $_.Selected }).Count -eq 0) "unsupported candidate is never selected"

    $badCandidate = New-CubismInstallationCandidate -Root $unsupported
    Assert-ManagedLaunch (-not $badCandidate.Selectable -and $badCandidate.Status -eq "Unsupported") "unsupported family candidate fails closed"
    $currentPowerShell = (Get-Process -Id $PID).Path
    if ([string]::IsNullOrWhiteSpace($currentPowerShell)) { throw "cannot resolve the current PowerShell executable" }
    $oneSelected = @($initial | ForEach-Object {
        [pscustomobject]@{
            CanonicalRoot = $_.CanonicalRoot
            Version = $_.Version
            Selected = $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $root53)
        }
    })
    Write-CubismInstallationState -StatePath $statePath -Candidates $oneSelected
    $oneGenericArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -ProbeOnly' -f (Join-Path $scriptDir "launch-cubism-turboism.ps1"), $turboismHome
    $oneGenericProcess = Start-Process -FilePath $currentPowerShell -ArgumentList $oneGenericArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($oneGenericProcess.ExitCode -eq 0) "valid one-selected generic probe succeeds"
    $aliasProbeHome = Join-Path $temp "configurator alias probe"
    New-Item -ItemType Directory -Path $aliasProbeHome -Force | Out-Null
    $aliasProbeArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -Cleanup' -f (Join-Path $scriptDir "configure_turboism.ps1"), $aliasProbeHome
    $aliasProbeProcess = Start-Process -FilePath $currentPowerShell -ArgumentList $aliasProbeArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($aliasProbeProcess.ExitCode -eq 0) "configurator public -Home alias binds and cleanup succeeds"

    $invalidState = [ordered]@{
        format = "turboism.cubism.installation-state"
        schemaVersion = 1
        installations = @(
            [ordered]@{ root = $root53; version = "5.3"; selected = $true }
            [ordered]@{ root = $unsupported; version = "5.4"; selected = $true }
        )
        managedShortcuts = @()
    }
    [System.IO.File]::WriteAllText($statePath, ($invalidState | ConvertTo-Json -Depth 5 -Compress), (New-Object System.Text.UTF8Encoding($false)))
    Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
    $stateLauncher = Join-Path $scriptDir "launch-cubism-turboism.ps1"
    $invalidStateArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}"' -f $stateLauncher, $turboismHome
    $invalidStateProcess = Start-Process -FilePath $currentPowerShell -ArgumentList $invalidStateArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($invalidStateProcess.ExitCode -ne 0) "valid plus stale selected state fails closed"
    Assert-ManagedLaunch (-not (Test-Path -LiteralPath $marker)) "invalid selected state fails before official BAT invocation"
    [System.IO.File]::WriteAllText($statePath, '{"format":"turboism.cubism.installation-state","schemaVersion":1,"installations":[]', (New-Object System.Text.UTF8Encoding($false)))
    Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
    $malformedStateArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -ProbeOnly' -f $stateLauncher, $turboismHome
    $malformedInfo = New-Object System.Diagnostics.ProcessStartInfo
    $malformedInfo.FileName = $currentPowerShell
    $malformedInfo.Arguments = $malformedStateArgs
    $malformedInfo.UseShellExecute = $false
    $malformedInfo.RedirectStandardOutput = $true
    $malformedInfo.RedirectStandardError = $true
    $malformedProcess = New-Object System.Diagnostics.Process
    $malformedProcess.StartInfo = $malformedInfo
    [void]$malformedProcess.Start()
    $malformedOutput = $malformedProcess.StandardOutput.ReadToEnd() + $malformedProcess.StandardError.ReadToEnd()
    $malformedProcess.WaitForExit()
    Assert-ManagedLaunch ($malformedProcess.ExitCode -ne 0 -and $malformedOutput -match 'Managed Cubism state is invalid|托管 Cubism 状态无效|管理対象 Cubism の状態が不正') "malformed state fails as localized StateInvalid"
    Assert-ManagedLaunch (-not (Test-Path -LiteralPath $marker)) "malformed state fails before official BAT invocation"
    Write-CubismInstallationState -StatePath $statePath -Candidates $initial

    $manualPolicyRoot = Join-Path $temp "saved-only Live2D Cubism 5.2"
    $autoPolicyRoot = Join-Path $temp "automatic Live2D Cubism 5.3"
    $manualPolicy = [pscustomobject]@{
        Root = $manualPolicyRoot; CanonicalRoot = (ConvertTo-CubismCanonicalRoot $manualPolicyRoot); Key = (Get-CubismRootKey $manualPolicyRoot)
        Version = "5.2"; Selectable = $true; Selected = $true
    }
    $autoPolicy = [pscustomobject]@{
        Root = $autoPolicyRoot; CanonicalRoot = (ConvertTo-CubismCanonicalRoot $autoPolicyRoot); Key = (Get-CubismRootKey $autoPolicyRoot)
        Version = "5.3"; Selectable = $true; Selected = $true
    }
    $policyState = @(
        [pscustomobject]@{ Root = $manualPolicyRoot; Version = "5.2"; Selected = $true }
        [pscustomobject]@{ Root = $autoPolicyRoot; Version = "5.3"; Selected = $true }
    )
    $policyResult = Remove-CubismCandidateEntries -Candidates @($manualPolicy, $autoPolicy) `
        -RemoveKeys @($manualPolicy.Key, $autoPolicy.Key) -StateInstallations $policyState `
        -AutomaticRootKeys @($autoPolicy.Key)
    Assert-ManagedLaunch (@($policyResult.Candidates | Where-Object { $_.Key -eq $manualPolicy.Key }).Count -eq 0) "saved-only valid manual root is forgotten"
    Assert-ManagedLaunch (@($policyResult.Candidates | Where-Object { $_.Key -eq $autoPolicy.Key -and -not $_.Selected }).Count -eq 1) "automatic valid root remains visible deselected"
    Assert-ManagedLaunch (@($policyResult.StateInstallations | Where-Object { $_.Root -eq $autoPolicyRoot -and -not $_.Selected }).Count -eq 1) "automatic deselection is persisted for rescan prevention"
    Assert-ManagedLaunch (@($policyResult.StateInstallations | Where-Object { $_.Root -eq $manualPolicyRoot }).Count -eq 0) "forgotten manual root is removed from saved state"

    $shortcutDir = Join-Path $temp "Start Menu\Turboism"
    $managedShortcut = Join-Path $shortcutDir (Get-CubismShortcutName $candidates[0])
    Assert-ManagedLaunch ((Get-CubismShortcutName $candidates[1]) -ne (Get-CubismShortcutName $candidates[2])) "duplicate installations get distinct shortcut identities"
    $unrelatedShortcut = Join-Path $shortcutDir "Turboism Configurator.lnk"
    $outsideShortcut = Join-Path $temp "outside.lnk"
    New-Item -ItemType Directory -Path $shortcutDir -Force | Out-Null
    Set-Content -LiteralPath $managedShortcut -Value "managed" -Encoding ASCII
    Set-Content -LiteralPath $unrelatedShortcut -Value "configure" -Encoding ASCII
    Set-Content -LiteralPath $outsideShortcut -Value "outside" -Encoding ASCII
    $ownedShortcutCleanup = @(Remove-CubismManagedShortcuts -Paths @($managedShortcut) -Directory $shortcutDir)
    Assert-ManagedLaunch ($ownedShortcutCleanup.Count -eq 0) "owned shortcut cleanup reports no failure"
    Assert-ManagedLaunch (-not (Test-Path -LiteralPath $managedShortcut)) "stale managed shortcut is removed"
    $unownedShortcutCleanup = @(Remove-CubismManagedShortcuts -Paths @($unrelatedShortcut, $outsideShortcut) -Directory $shortcutDir)
    Assert-ManagedLaunch ($unownedShortcutCleanup.Count -eq 2) "unowned shortcut cleanup reports every rejected path"
    Assert-ManagedLaunch ((Test-Path -LiteralPath $unrelatedShortcut) -and (Test-Path -LiteralPath $outsideShortcut)) "shortcut cleanup is confined to owned entries"

    $comShortcut = $null
    try {
        $comShortcut = New-CubismManagedShortcut -TurboismHome $turboismHome -Candidate $candidates[0] -Variant "normal" -ShortcutDirectory $shortcutDir
        Assert-ManagedLaunch (Test-Path -LiteralPath $comShortcut -PathType Leaf) "COM creates a real managed .lnk in the bounded directory"
        Assert-ManagedLaunch (@(Remove-CubismManagedShortcuts -Paths @($comShortcut) -Directory $shortcutDir).Count -eq 0) "COM-created shortcut is removed through the same ownership guard"
    }
    catch {
        if ($env:OS -eq "Windows_NT") { throw }
        Write-Host "ok: COM shortcut workflow not available on this host"
    }

    $bat = Join-Path $root53 "CubismEditor5.bat"
    $d3dBat = Join-Path $root53 "CubismEditor5_D3D.bat"
    $agent = Join-Path $turboismHome "turboism-agent.jar"
    $beforeBat = (Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash
    $beforeD3D = (Get-FileHash -LiteralPath $d3dBat -Algorithm SHA256).Hash
    $beforeAgent = (Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash
    $applicationJar = Join-Path $root53 "app\lib\Live2D_Cubism.jar"
    $beforeApplicationJar = (Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash
    $env:TURBOISM_TEST_OUTPUT = $marker
    $oldJdk = $env:JDK_JAVA_OPTIONS
    $oldTool = $env:JAVA_TOOL_OPTIONS
    $env:JDK_JAVA_OPTIONS = '-Xmx192m -javaagent:old-turboism-agent.jar -Dturboism.home=old-home --add-exports=java.base.jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports=java.base.jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED'
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -javaagent:old-turboism-agent.jar -Dturboism.home=old-home --add-exports=java.base.jdk.internal.org.objectweb.asm=ALL-UNNAMED'
    try {
        $exitCode = Invoke-CubismOfficialBat -OfficialBat $bat -CubismRoot $root53 -TurboismHome $turboismHome -Agent $agent -Arguments @("fixture argument")
        Assert-ManagedLaunch ($exitCode -eq 23) "official normal BAT exit code is propagated"
        Assert-ManagedLaunch ((Get-Content -LiteralPath $marker -Raw) -match 'NORMAL=1') "normal launch invokes the official BAT"
        $markerText = Get-Content -LiteralPath $marker -Raw
        Assert-ManagedLaunch (([regex]::Matches($markerText, '-javaagent:')).Count -eq 1) "Turboism agent is attached exactly once"
        Assert-ManagedLaunch ($markerText -match '-javaagent:.*turboism-agent\.jar') "current Turboism agent is inherited"
        Assert-ManagedLaunch ($markerText -notmatch 'old-turboism-agent\.jar') "stale Turboism agent is removed"
        Assert-ManagedLaunch (([regex]::Matches($markerText, '--add-exports=java.base.jdk.internal.org.objectweb.asm=ALL-UNNAMED')).Count -eq 1) "base ASM export is attached exactly once"
        Assert-ManagedLaunch (([regex]::Matches($markerText, '--add-exports=java.base.jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED')).Count -eq 1) "commons ASM export is attached exactly once"
        Assert-ManagedLaunch ($markerText -notmatch 'old-home') "stale Turboism home option is removed"
        Assert-ManagedLaunch ((Get-Content -LiteralPath $marker -Raw) -match '-Xmx192m') "unrelated pre-existing JVM option is preserved"
        Assert-ManagedLaunch ($markerText -match 'TOOL=-Dfile.encoding=UTF-8' -and $markerText -notmatch 'TOOL=.*old-turboism-agent') "unrelated tool JVM option is preserved and stale attachment is removed"
        Assert-ManagedLaunch ((Get-Content -LiteralPath $marker -Raw) -match 'ARG1=fixture argument') "arguments with spaces reach the official BAT"
        $env:JDK_JAVA_OPTIONS = '-Xmx1 "unmatched'
        $malformedThrew = $false
        try { [void](Invoke-CubismOfficialBat -OfficialBat $bat -CubismRoot $root53 -TurboismHome $turboismHome -Agent $agent) } catch { $malformedThrew = $true }
        Assert-ManagedLaunch $malformedThrew "unsupported JVM quoting fails closed"
        Assert-ManagedLaunch ($env:JDK_JAVA_OPTIONS -eq '-Xmx1 "unmatched') "failed launch restores malformed parent options"
    }
    finally {
        if ($null -eq $oldJdk) { Remove-Item Env:JDK_JAVA_OPTIONS -ErrorAction SilentlyContinue } else { $env:JDK_JAVA_OPTIONS = $oldJdk }
        if ($null -eq $oldTool) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue } else { $env:JAVA_TOOL_OPTIONS = $oldTool }
    }
    Assert-ManagedLaunch ((($null -eq $oldJdk) -and -not (Test-Path Env:JDK_JAVA_OPTIONS)) -or (($null -ne $oldJdk) -and $env:JDK_JAVA_OPTIONS -eq $oldJdk)) "parent JDK options are restored"
    Assert-ManagedLaunch ((($null -eq $oldTool) -and -not (Test-Path Env:JAVA_TOOL_OPTIONS)) -or (($null -ne $oldTool) -and $env:JAVA_TOOL_OPTIONS -eq $oldTool)) "parent tool options are restored"
    Assert-ManagedLaunch ((Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash -eq $beforeBat) "normal official BAT is unchanged"
    Assert-ManagedLaunch ((Get-FileHash -LiteralPath $d3dBat -Algorithm SHA256).Hash -eq $beforeD3D) "D3D official BAT is unchanged"
    Assert-ManagedLaunch ((Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash -eq $beforeAgent) "Turboism agent is unchanged"
    Assert-ManagedLaunch ((Get-FileHash -LiteralPath $applicationJar -Algorithm SHA256).Hash -eq $beforeApplicationJar) "synthetic Cubism application JAR is unchanged"

    $ps = $currentPowerShell
    Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
    Assert-ManagedLaunch (-not (Test-Path -LiteralPath $statePath)) "explicit root remains independent of missing state"
    $launcher = Join-Path $scriptDir "launch-cubism-turboism.ps1"
    $launchArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -CubismRoot "{2}" -Variant d3d' -f $launcher, $turboismHome, $root53
    $process = Start-Process -FilePath $ps -ArgumentList $launchArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($process.ExitCode -eq 23) "launcher selects and invokes the explicit D3D BAT"
    Assert-ManagedLaunch ((Get-Content -LiteralPath $marker -Raw) -match 'D3D=1') "D3D launcher entry is distinct"
    Write-CubismInstallationState -StatePath $statePath -Candidates $initial

    $genericArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -ProbeOnly' -f $launcher, $turboismHome
    $genericInfo = New-Object System.Diagnostics.ProcessStartInfo
    $genericInfo.FileName = $ps
    $genericInfo.Arguments = $genericArgs
    $genericInfo.UseShellExecute = $false
    $genericInfo.RedirectStandardInput = $true
    $genericInfo.RedirectStandardOutput = $true
    $genericInfo.RedirectStandardError = $true
    $generic = New-Object System.Diagnostics.Process
    $generic.StartInfo = $genericInfo
    [void]$generic.Start()
    $generic.StandardInput.WriteLine("2")
    $generic.StandardInput.Close()
    $genericOutput = $generic.StandardOutput.ReadToEnd()
    [void]$generic.StandardError.ReadToEnd()
    $generic.WaitForExit()
    Assert-ManagedLaunch ($generic.ExitCode -eq 0 -and $genericOutput -match [regex]::Escape((ConvertTo-CubismCanonicalRoot $root53DuplicateVersion))) "multiple-selected generic launch requires an explicit choice"

    $badArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -CubismRoot "{2}" -ProbeOnly' -f $launcher, $turboismHome, $unsupported
    $badProcess = Start-Process -FilePath $ps -ArgumentList $badArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($badProcess.ExitCode -ne 0) "explicit unsupported root is rejected before launch"

    # R12: backup confinement is exact-name (relative installer/shortcut-backups/<64-hex>.lnk).
    $relocatedHome = Join-Path $temp "confined backup home"
    New-Item -ItemType Directory -Path $relocatedHome -Force | Out-Null
    $hex64 = ('a' * 64)
    Assert-ManagedLaunch (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer\shortcut-backups\$hex64.lnk") "canonical backslash backup spelling is confined"
    Assert-ManagedLaunch (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer/shortcut-backups/$hex64.lnk") "canonical forward-slash backup spelling is confined"
    Assert-ManagedLaunch (-not (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer\other\$hex64.lnk")) "home-confined relocated backup path is rejected"
    Assert-ManagedLaunch (-not (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer\shortcut-backups\$hex64.lnk\extra")) "nested backup path is rejected"
    Assert-ManagedLaunch (-not (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer\shortcut-backups\$hex64")) "alternate backup file name is rejected"
    Assert-ManagedLaunch (-not (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "installer\shortcut-backups\..\$hex64.lnk")) "backup traversal is rejected"
    Assert-ManagedLaunch (-not (Test-CubismConfinedBackupPath -TurboismHome $relocatedHome -RelativePath "$relocatedHome\installer\shortcut-backups\$hex64.lnk")) "absolute backup path is rejected"
    $relocatedRecord = [pscustomobject]@{
        ShortcutPath = (Join-Path $temp "fake.lnk"); BackupPath = "installer\other\$hex64.lnk"
        OriginalSha256 = ('B' * 64); ManagedSha256 = ('C' * 64); Root = $root53; Variant = "normal"; Status = "active"
    }
    $relocatedWriteThrew = $false
    try { Write-CubismInstallationState -StatePath (Join-Path $relocatedHome "cubism-installations.json") -Candidates @() -ShortcutTakeovers @($relocatedRecord) } catch { $relocatedWriteThrew = $true }
    Assert-ManagedLaunch $relocatedWriteThrew "state writer rejects a relocated backup record below home"

    # R12: a linked backup directory fails closed at use time (restore and delete).
    $linkedHome = Join-Path $temp "linked backup home"
    $linkedTarget = Join-Path $temp "linked backup escape"
    $linkedShortcut = Join-Path $temp "linked-target.lnk"
    New-Item -ItemType Directory -Path (Join-Path $linkedHome "installer\shortcut-backups"), $linkedTarget -Force | Out-Null
    $linkedBackupName = $hex64 + ".lnk"
    $linkedBackup = Join-Path (Join-Path $linkedHome "installer") (Join-Path "shortcut-backups" $linkedBackupName)
    $linkedOriginal = "original-shortcut-bytes"
    $linkedManaged = "managed-shortcut-bytes"
    $linkedRecord = [pscustomobject]@{
        ShortcutPath = $linkedShortcut; BackupPath = "installer/shortcut-backups/$linkedBackupName"
        OriginalSha256 = (Get-CubismTextSha256 $linkedOriginal); ManagedSha256 = (Get-CubismTextSha256 $linkedManaged)
        Root = $root53; Variant = "normal"; Status = "active"
    }
    try {
        [System.IO.File]::WriteAllText($linkedShortcut, $linkedManaged)
        [System.IO.File]::WriteAllText($linkedBackup, $linkedOriginal)
        [void](Restore-CubismTakeoverRecords -TurboismHome $linkedHome -Records @($linkedRecord))
        Assert-ManagedLaunch ((Get-Content -LiteralPath $linkedShortcut -Raw) -eq $linkedOriginal) "normal backup chain restores exact bytes"
        [System.IO.File]::WriteAllText($linkedShortcut, $linkedManaged)
        Remove-Item -LiteralPath $linkedBackup -Force
        Remove-Item -LiteralPath (Join-Path $linkedHome "installer\shortcut-backups") -Force
        $linkedFixtureAvailable = $false
        try {
            New-Item -ItemType SymbolicLink -Path (Join-Path $linkedHome "installer\shortcut-backups") -Target $linkedTarget -ErrorAction Stop | Out-Null
            $linkedFixtureAvailable = $true
        }
        catch { Write-Host "ok: linked backup directory fixture unavailable on this host" }
        if ($linkedFixtureAvailable) {
            $restoreLinkedThrew = $false
            try { [void](Restore-CubismTakeoverRecords -TurboismHome $linkedHome -Records @($linkedRecord)) } catch { $restoreLinkedThrew = $true }
            Assert-ManagedLaunch $restoreLinkedThrew "restore fails closed when the backup directory is a reparse link"
            Assert-ManagedLaunch ((Get-Content -LiteralPath $linkedShortcut -Raw) -eq $linkedManaged) "linked backup directory never mutates the current shortcut"
            $deleteLinkedThrew = $false
            try { [void](Remove-CubismTakeoverBackups -TurboismHome $linkedHome -Records @($linkedRecord)) } catch { $deleteLinkedThrew = $true }
            Assert-ManagedLaunch $deleteLinkedThrew "backup deletion fails closed when the backup directory is a reparse link"
            Assert-ManagedLaunch (Test-Path -LiteralPath $linkedTarget) "linked backup directory target is never deleted"
        }
    }
    finally {
        Remove-Item -LiteralPath (Join-Path $linkedHome "installer\shortcut-backups") -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $linkedHome, $linkedTarget, $linkedShortcut -Recurse -Force -ErrorAction SilentlyContinue
    }

    # R5 dual-mode fixture: only current-user Desktop/Start Menu roots are used.
    # The fixture creates real .lnk bytes through WScript.Shell; no Cubism host is
    # launched and all files remain inside the temporary home/fixture directory.
    if ($env:OS -eq "Windows_NT") {
        function New-TestShortcut {
            param([string]$Path, [string]$Target, [string]$Arguments = "")
            $shell = $null; $shortcut = $null
            try {
                $shell = New-Object -ComObject WScript.Shell
                $shortcut = $shell.CreateShortcut($Path)
                $shortcut.TargetPath = $Target
                $shortcut.Arguments = $Arguments
                $shortcut.WorkingDirectory = Split-Path -Parent $Target
                $shortcut.Save()
            }
            finally {
                if ($null -ne $shortcut) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) }
                if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) }
            }
        }
        $userRoots = @(Get-CubismCurrentUserShortcutRoots)
        Assert-ManagedLaunch ($userRoots.Count -gt 0) "current-user Desktop or Start Menu root is available"
        $fixtureShortcutRoot = Join-Path $userRoots[0] ("Turboism dual-mode fixture " + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $fixtureShortcutRoot -Force | Out-Null
        $normalShortcut = Join-Path $fixtureShortcutRoot "Cubism Normal.lnk"
        $d3dShortcut = Join-Path $fixtureShortcutRoot "Cubism D3D.lnk"
        $wrongShortcut = Join-Path $fixtureShortcutRoot "Cubism Wrong Target.lnk"
        New-TestShortcut -Path $normalShortcut -Target $bat
        New-TestShortcut -Path $d3dShortcut -Target $d3dBat
        New-TestShortcut -Path $wrongShortcut -Target (Join-Path $root52 "CubismEditor5.bat")
        $beforeNormal = (Get-FileHash -LiteralPath $normalShortcut -Algorithm SHA256).Hash
        $beforeD3D = (Get-FileHash -LiteralPath $d3dShortcut -Algorithm SHA256).Hash
        $takeoverCandidates = @($candidates | ForEach-Object {
            $_.Selected = $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $root53)
            $_
        })
        $matches = @(Get-CubismTakeoverMatches -Candidates $takeoverCandidates -ShortcutRoots @($fixtureShortcutRoot))
        Assert-ManagedLaunch ($matches.Count -eq 2) "takeover discovery matches exact normal and D3D targets"
        Assert-ManagedLaunch (@($matches | Where-Object { $_.Variant -eq "normal" }).Count -eq 1) "normal target is classified separately"
        Assert-ManagedLaunch (@($matches | Where-Object { $_.Variant -eq "d3d" }).Count -eq 1) "D3D target is classified separately"
        Assert-ManagedLaunch (@($matches | Where-Object { $_.ShortcutPath -eq $wrongShortcut }).Count -eq 0) "wrong-target shortcut is ignored"
        Assert-ManagedLaunch (-not (Test-CubismTakeoverShortcutPath -Path (Join-Path $temp "not-user.lnk"))) "takeover path cannot escape current-user roots"

        $takeoverResult = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "takeover" -ExistingState (Read-CubismInstallationState -StatePath $statePath) -ShortcutDirectory $fixtureShortcutRoot
        $takeoverState = Read-CubismInstallationState -StatePath $statePath
        Assert-ManagedLaunch ($takeoverState.Valid -and @($takeoverState.ShortcutTakeovers).Count -eq 2) "takeover mode persists one bounded record per eligible shortcut"
        Assert-ManagedLaunch ((Get-FileHash -LiteralPath $normalShortcut -Algorithm SHA256).Hash -ne $beforeNormal) "normal shortcut is replaced"
        Assert-ManagedLaunch ((Get-FileHash -LiteralPath $d3dShortcut -Algorithm SHA256).Hash -ne $beforeD3D) "D3D shortcut is replaced"
        $shell = New-Object -ComObject WScript.Shell
        $normalProperties = $null; $d3dProperties = $null
        try {
            $normalProperties = $shell.CreateShortcut($normalShortcut)
            $d3dProperties = $shell.CreateShortcut($d3dShortcut)
            Assert-ManagedLaunch ($normalProperties.Arguments -match '(?i)-Home .* -CubismRoot .* -Variant normal') "normal takeover has explicit launcher arguments"
            Assert-ManagedLaunch ($d3dProperties.Arguments -match '(?i)-Home .* -CubismRoot .* -Variant d3d') "D3D takeover has explicit launcher arguments"
        }
        finally {
            if ($null -ne $normalProperties) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($normalProperties) }
            if ($null -ne $d3dProperties) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($d3dProperties) }
            [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
        }
        $takeoverAgain = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "takeover" -ExistingState $takeoverState -ShortcutDirectory $fixtureShortcutRoot
        $takeoverAgainState = Read-CubismInstallationState -StatePath $statePath
        Assert-ManagedLaunch (@($takeoverAgainState.ShortcutTakeovers).Count -eq 2) "reapplying takeover is idempotent and does not duplicate records"
        Assert-ManagedLaunch ($takeoverAgainState.ShortcutTakeovers[0].BackupPath -ne "") "takeover backup path remains bounded and recorded"

        $preFallbackResult = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "independent" -ExistingState $takeoverAgainState -ShortcutDirectory $fixtureShortcutRoot
        $preFallbackState = Read-CubismInstallationState -StatePath $statePath
        Remove-Item -LiteralPath $d3dShortcut -Force
        $fallbackResult = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "takeover" -ExistingState $preFallbackState -ShortcutDirectory $fixtureShortcutRoot
        $fallbackState = Read-CubismInstallationState -StatePath $statePath
        Assert-ManagedLaunch (@($fallbackState.ShortcutTakeovers).Count -eq 1) "unmatched D3D variant remains out of takeover records"
        Assert-ManagedLaunch (@($fallbackState.ManagedShortcuts).Count -eq 1) "unmatched variant receives an independent fallback shortcut"
        Assert-ManagedLaunch (@($fallbackState.ManagedShortcutHashes).Count -eq 1) "fallback shortcut records its managed hash"

        $independentResult = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "independent" -ExistingState $fallbackState -ShortcutDirectory $fixtureShortcutRoot
        $independentState = Read-CubismInstallationState -StatePath $statePath
        Assert-ManagedLaunch (@($independentState.ShortcutTakeovers).Count -eq 0) "switching to independent mode restores all takeovers"
        Assert-ManagedLaunch ((Get-FileHash -LiteralPath $normalShortcut -Algorithm SHA256).Hash -eq $beforeNormal) "mode switch restores exact normal bytes"
        Assert-ManagedLaunch (-not (Test-Path -LiteralPath $d3dShortcut)) "mode switch does not recreate unmatched D3D original"

        $conflictResult = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "takeover" -ExistingState $independentState -ShortcutDirectory $fixtureShortcutRoot
        $conflictState = Read-CubismInstallationState -StatePath $statePath
        New-TestShortcut -Path $normalShortcut -Target (Join-Path $root52 "CubismEditor5.bat") -Arguments "user-edit"
        $conflictThrew = $false
        try { [void](Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $takeoverCandidates -LaunchMode "takeover" -ExistingState $conflictState -ShortcutDirectory $fixtureShortcutRoot) } catch { $conflictThrew = $true }
        Assert-ManagedLaunch $conflictThrew "user-edited takeover shortcut fails closed"
        Assert-ManagedLaunch ((Get-FileHash -LiteralPath $normalShortcut -Algorithm SHA256).Hash -ne $conflictState.ShortcutTakeovers[0].ManagedSha256) "conflicting shortcut bytes are preserved"
        Assert-ManagedLaunch (Test-Path -LiteralPath $statePath) "conflict preserves managed state for retry"
        Assert-ManagedLaunch (@(Get-ChildItem -LiteralPath (Join-Path $turboismHome "installer\shortcut-backups") -Filter *.lnk -File).Count -gt 0) "conflict preserves original shortcut backup"

        $tooMany = @(0..128 | ForEach-Object { [pscustomobject]@{ ShortcutPath = $normalShortcut; BackupPath = "installer/shortcut-backups/$_.lnk"; OriginalSha256 = ('A' * 64); ManagedSha256 = ('B' * 64); Root = $root53; Variant = "normal"; Status = "active" } })
        $capThrew = $false
        try { Write-CubismInstallationState -StatePath $statePath -Candidates $takeoverCandidates -ShortcutTakeovers $tooMany } catch { $capThrew = $true }
        Assert-ManagedLaunch $capThrew "takeover record cap rejects the 129th record"

        # R12: a home-confined relocated backup record is rejected by the state
        # writer and reader (a valid current-user shortcut path is required).
        $r12Hex = ('a' * 64)
        $r12RelocatedRecord = [pscustomobject]@{
            ShortcutPath = $normalShortcut; BackupPath = "installer\other\$r12Hex.lnk"
            OriginalSha256 = ('B' * 64); ManagedSha256 = ('C' * 64); Root = $root53; Variant = "normal"; Status = "active"
        }
        $r12RelocatedWriteThrew = $false
        try { Write-CubismInstallationState -StatePath $statePath -Candidates $takeoverCandidates -ShortcutTakeovers @($r12RelocatedRecord) } catch { $r12RelocatedWriteThrew = $true }
        Assert-ManagedLaunch $r12RelocatedWriteThrew "state writer rejects a home-confined relocated backup record"
        [System.IO.File]::WriteAllText($statePath, ([ordered]@{
            format = "turboism.cubism.installation-state"; schemaVersion = 1; installations = @(); managedShortcuts = @()
            launchMode = "takeover"
            shortcutTakeovers = @([ordered]@{
                shortcutPath = $normalShortcut; backupPath = "installer\other\$r12Hex.lnk"
                originalSha256 = ('B' * 64); managedSha256 = ('C' * 64); root = $root53; variant = "normal"; status = "active"
            })
        } | ConvertTo-Json -Depth 8 -Compress), (New-Object System.Text.UTF8Encoding($false)))
        $r12RelocatedRead = Read-CubismInstallationState -StatePath $statePath
        Assert-ManagedLaunch (-not $r12RelocatedRead.Valid -and $r12RelocatedRead.Error -match 'outside an allowed boundary') "state reader rejects a home-confined relocated backup record"

        # R12: a forced publication failure after the pending takeover record is
        # durable must preserve state, backup, and the current shortcut. The
        # Publish-CubismStagedShortcut override is captured, installed via
        # Set-Item Function:, and restored in the finally path below.
        $r12Home = Join-Path $temp "r12 failure home"
        $r12State = Join-Path $r12Home "cubism-installations.json"
        New-Item -ItemType Directory -Path $r12Home -Force | Out-Null
        $r12ShortcutRoot = Join-Path $userRoots[0] ("Turboism r12 fixture " + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $r12ShortcutRoot -Force | Out-Null
        $r12Shortcut = Join-Path $r12ShortcutRoot "Cubism Normal.lnk"
        New-TestShortcut -Path $r12Shortcut -Target $bat
        $r12OriginalHash = (Get-FileHash -LiteralPath $r12Shortcut -Algorithm SHA256).Hash
        $r12Candidates = @($candidates | ForEach-Object {
            [pscustomobject]@{
                CanonicalRoot = $_.CanonicalRoot; Version = $_.Version
                Selected = $_.CanonicalRoot -eq (ConvertTo-CubismCanonicalRoot $root53); Selectable = $_.Selectable
                OfficialBat = $_.OfficialBat; D3DBat = $_.D3DBat
            }
        })
        Write-CubismInstallationState -StatePath $r12State -Candidates $r12Candidates
        $r12SavedPublish = (Get-Item Function:Publish-CubismStagedShortcut).ScriptBlock
        $r12PublishThrew = $false
        try {
            Set-Item Function:Publish-CubismStagedShortcut -Value { throw "forced publication failure after pending state" }
            try { [void](Invoke-CubismLaunchConfiguration -TurboismHome $r12Home -StatePath $r12State -Candidates $r12Candidates -LaunchMode "takeover" -ShortcutDirectory $r12ShortcutRoot) } catch { $r12PublishThrew = $true }
            Assert-ManagedLaunch $r12PublishThrew "publication failure after durable pending state fails closed"
            $r12StateAfter = Read-CubismInstallationState -StatePath $r12State
            Assert-ManagedLaunch ($r12StateAfter.Valid -and @($r12StateAfter.ShortcutTakeovers).Count -eq 1 -and $r12StateAfter.ShortcutTakeovers[0].Status -eq "pending") "pending takeover record survives the publication failure"
            Assert-ManagedLaunch ($r12StateAfter.ShortcutTakeovers[0].BackupPath -match '^installer[\\/]shortcut-backups[\\/][0-9A-Fa-f]{64}\.lnk$') "pending record keeps a confined backup path"
            $r12BackupDir = Join-Path $r12Home "installer\shortcut-backups"
            $r12Backups = @(Get-ChildItem -LiteralPath $r12BackupDir -Filter *.lnk -File -ErrorAction SilentlyContinue)
            Assert-ManagedLaunch ($r12Backups.Count -eq 1) "newly-created backup survives the publication failure"
            Assert-ManagedLaunch ((Get-FileHash -LiteralPath $r12Shortcut -Algorithm SHA256).Hash -eq $r12OriginalHash) "original shortcut survives the publication failure"
            Assert-ManagedLaunch ($r12Backups.Count -eq 1 -and (Get-CubismSha256 $r12Backups[0].FullName) -eq $r12OriginalHash) "surviving backup holds the exact original bytes"
            Assert-ManagedLaunch (@(Get-ChildItem -LiteralPath $r12ShortcutRoot -Force -Filter ".turboism-*").Count -eq 0) "staged temporary shortcut is removed after the forced failure"
        }
        finally {
            Set-Item Function:Publish-CubismStagedShortcut -Value $r12SavedPublish
            Remove-Item -LiteralPath $r12ShortcutRoot -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $r12Home -Recurse -Force -ErrorAction SilentlyContinue
        }
        Assert-ManagedLaunch ((Get-Item Function:Publish-CubismStagedShortcut).ScriptBlock.ToString() -eq $r12SavedPublish.ToString()) "production publication helper is restored"
        Assert-ManagedLaunch ((-not (Test-Path -LiteralPath $r12ShortcutRoot)) -and (-not (Test-Path -LiteralPath $r12Home))) "R12 fixture and override cleanup is deterministic"
        Remove-Item -LiteralPath $fixtureShortcutRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host "MANAGED_LAUNCH_TEST=PASS"
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item Env:TURBOISM_TEST_OUTPUT -ErrorAction SilentlyContinue
}
