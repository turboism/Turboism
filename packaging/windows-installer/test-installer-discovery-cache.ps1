# Synthetic end-to-end discovery handoff; never executes a host Java or BAT.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
$script:Assertions = 0
function Assert-DiscoveryCache {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Discovery cache regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}
function Assert-DiscoveryCacheThrows {
    param([scriptblock]$Action, [string]$Message)
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert-DiscoveryCache $failed $Message
}

# Exercise the production wire parser, not only the post-parse resolver seam.
$probeDocument = [ordered]@{
    schemaVersion = 2; status = 'COMPATIBLE'; reason = 'structural preflight passed'
    runtimeAdmitted = $true; identity = [ordered]@{ version = '5.3.99' }
    evidenceStage = 'STATIC_PREFLIGHT'; runtimeHooksVerified = $false
}
$probeJson = $probeDocument | ConvertTo-Json -Depth 8
$parsedProbe = ConvertFrom-CubismHostProbeResult -JsonText $probeJson -ExitCode 0
Assert-DiscoveryCache ($null -ne $parsedProbe -and $parsedProbe.Version -ceq '5.3.99' -and $parsedProbe.CompatStatus -ceq 'COMPATIBLE') 'schema v2 compatible result keeps the unknown declared version'
$probeDocument.status = 'VERIFIED'
$probeDocument.identity.version = '5.3.02'
$parsedProbe = ConvertFrom-CubismHostProbeResult -JsonText ($probeDocument | ConvertTo-Json -Depth 8) -ExitCode 0
Assert-DiscoveryCache ($null -ne $parsedProbe -and $parsedProbe.CompatStatus -ceq 'VERIFIED') 'schema v2 reviewed result is accepted'
$probeDocument.runtimeAdmitted = $false
$probeDocument.reason = 'base runtime capability did not resolve'
$parsedProbe = ConvertFrom-CubismHostProbeResult -JsonText ($probeDocument | ConvertTo-Json -Depth 8) -ExitCode 1
Assert-DiscoveryCache ($parsedProbe.CompatStatus -ceq 'REJECTED' -and $parsedProbe.Version -ceq '5.3.02' -and $parsedProbe.Reason -ceq $probeDocument.reason) 'exit 1 preserves identity and the admission failure'
$probeDocument.status = 'REJECTED'
$probeDocument.identity = [ordered]@{}
$parsedProbe = ConvertFrom-CubismHostProbeResult -JsonText ($probeDocument | ConvertTo-Json -Depth 8) -ExitCode 1
Assert-DiscoveryCache ($null -ne $parsedProbe -and $parsedProbe.Version -ceq '' -and $parsedProbe.CompatStatus -ceq 'REJECTED') 'identity rejection with no version is retained'
$probeDocument.schemaVersion = 1
Assert-DiscoveryCache ($null -eq (ConvertFrom-CubismHostProbeResult -JsonText ($probeDocument | ConvertTo-Json -Depth 8) -ExitCode 1)) 'obsolete probe schema is rejected'
$probeDocument.schemaVersion = 2
$probeDocument.runtimeAdmitted = 'true'
Assert-DiscoveryCache ($null -eq (ConvertFrom-CubismHostProbeResult -JsonText ($probeDocument | ConvertTo-Json -Depth 8) -ExitCode 0)) 'text cannot substitute for the runtime admission boolean'
Assert-DiscoveryCache ($null -eq (ConvertFrom-CubismHostProbeResult -JsonText '{}' -ExitCode 0)) 'missing probe fields fail closed under strict mode'
Assert-DiscoveryCache ($null -eq (ConvertFrom-CubismHostProbeResult -JsonText '{' -ExitCode 0)) 'malformed probe JSON fails closed'
Assert-DiscoveryCache ($null -eq (ConvertFrom-CubismHostProbeResult -JsonText $probeJson -ExitCode 2)) 'probe process errors cannot become compatible'

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('turboism-discovery-regression-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($fixture)
try {
    $homePath = Join-Path $fixture 'Turboism home 中文'
    $scanDrive = Join-Path $fixture 'D-shaped-drive'
    $root = Join-Path $scanDrive 'L2D/Live2D Cubism 5.3'
    foreach ($directory in @($homePath, (Join-Path $root 'app/lib'), (Join-Path $root 'app/jre/bin'))) {
        [void][System.IO.Directory]::CreateDirectory($directory)
    }
    $agent = Join-Path $homePath 'turboism-agent.jar'
    $jar = Join-Path $root 'app/lib/Live2D_Cubism.jar'
    $java = Join-Path $root 'app/jre/bin/java.exe'
    $bat = Join-Path $root 'CubismEditor5.bat'
    [System.IO.File]::WriteAllText($agent, 'synthetic verifier, not executable')
    [System.IO.File]::WriteAllText($jar, 'synthetic reviewed artifact')
    [System.IO.File]::WriteAllText($java, 'synthetic Java, not executable')
    [System.IO.File]::WriteAllText($bat, "@echo off`r`nrem synthetic BAT, never executed`r`n")
    # Reported installation: D:\L2D\Live2D Cubism 5.3 (artifact version 5.3.04).
    # Exercise real root enumeration before replacing discovery with the cache seam.
    $scanRoots = @(Get-CubismShallowDiscoveryRoots -DriveRoots @($scanDrive))
    Assert-DiscoveryCache ($scanRoots.Count -eq 1 -and $scanRoots[0] -eq (ConvertTo-CubismCanonicalRoot $root)) 'fixed-drive scan finds Cubism below the L2D container'
    $script:ProbeCalls = 0
    $script:DiscoveryCalls = 0
    $script:FixtureRoot = $root
    $script:CubismArtifactVersionResolver = { param($j, $a, $h) $script:ProbeCalls++; return '5.3.04' }
    function Get-CubismDiscoveryRoots { $script:DiscoveryCalls++; return @($script:FixtureRoot) }

    $report = Join-Path $homePath 'cubism-scan.result'
    Write-CubismInstallerDiscoveryReport -TurboismHome $homePath -OutputPath $report | Out-Null
    $snapshot = $report + '.json'
    Assert-DiscoveryCache (Test-Path -LiteralPath $snapshot -PathType Leaf) 'middle scan publishes a structured snapshot'
    $lines = @([System.IO.File]::ReadAllLines($report, [System.Text.Encoding]::Unicode))
    $hashRecords = @($lines | Where-Object { $_ -match '^SNAPSHOT\|[0-9a-fA-F]{64}$' })
    Assert-DiscoveryCache ($hashRecords.Count -eq 1) 'report binds exactly one snapshot SHA-256'
    $snapshotHash = $hashRecords[0].Substring(9)
    Assert-DiscoveryCache ($snapshotHash -ieq (Get-CubismSha256 $snapshot)) 'published snapshot matches its report digest'
    Assert-DiscoveryCache ($script:DiscoveryCalls -eq 1 -and $script:ProbeCalls -eq 1) 'scan enumerates and verifies the synthetic host once'
    $snapshotBytes = [System.IO.File]::ReadAllBytes($snapshot)

    function Get-CubismDiscoveryRoots { throw 'BROAD DISCOVERY MUST NOT RUN AFTER THE MIDDLE PAGE' }
    $script:CubismArtifactVersionResolver = { throw 'JAVA VERSION PROBE MUST NOT RUN AFTER THE MIDDLE PAGE' }
    $cached = @(Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash)
    Assert-DiscoveryCache ($cached.Count -eq 1 -and $cached[0].Version -ceq '5.3.04' -and $cached[0].Selectable) 'cached exact version is reused without discovery or Java'
    Assert-DiscoveryCache ($cached[0].OfficialBat -eq $bat -and $cached[0].ApplicationJar -eq $jar) 'operational paths are derived from the admitted root'
    $selected = @(Merge-CubismSelection -Candidates $cached -SavedInstallations @([pscustomobject]@{ Root=$root; Selected=$false }))
    Assert-DiscoveryCache (-not $selected[0].Selected) 'saved deselection survives snapshot import'

    # Real configurator subprocesses use only synthetic non-executable host files.
    # If they fall back to Java probing, their state loses the exact supported version.
    $powershell = (Get-Process -Id $PID).Path
    $configurator = Join-Path $PSScriptRoot 'configure_turboism.ps1'
    & $powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $configurator -Home $homePath -InitializeSelection -InstallerDiscoverySnapshot $snapshot -InstallerDiscoverySnapshotSha256 $snapshotHash
    Assert-DiscoveryCache ($LASTEXITCODE -eq 0) 'configurator imports the middle scan successfully'
    $statePath = Join-Path $homePath 'cubism-installations.json'
    $state = Read-CubismInstallationState -StatePath $statePath
    Assert-DiscoveryCache ($state.Valid -and $state.Installations.Count -eq 1 -and $state.Installations[0].Version -ceq '5.3.04' -and $state.Installations[0].Selected) 'headless initialization preserves exact candidate and selection'
    & $powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $configurator -Home $homePath -DisableShortcuts -InstallerDiscoverySnapshot $snapshot -InstallerDiscoverySnapshotSha256 $snapshotHash
    Assert-DiscoveryCache ($LASTEXITCODE -eq 0) 'headless shortcut operation consumes the same snapshot'
    $state = Read-CubismInstallationState -StatePath $statePath
    Assert-DiscoveryCache ($state.Valid -and $state.Installations[0].Version -ceq '5.3.04') 'shortcut operation never reprobes synthetic Java'

    # Reproduce the full reinstall sequence with an already managed BAT.
    $originalBatBytes = [IO.File]::ReadAllBytes($bat)
    & $powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $configurator -Home $homePath -IntegrateBat -Elevated -InstallerDiscoverySnapshot $snapshot -InstallerDiscoverySnapshotSha256 $snapshotHash
    Assert-DiscoveryCache ($LASTEXITCODE -eq 0) 'first elevated BAT integration consumes the snapshot'
    $integratedState = Read-CubismInstallationState -StatePath $statePath
    # Reported legacy state has no batIntegrations despite a managed BAT.
    $oldOptions = (ConvertTo-JdkOptionToken "-Dturboism.home=$homePath") + ' ' + (ConvertTo-JdkOptionToken "-javaagent:$(Join-Path $homePath 'turboism-agent.jar')=home=$homePath;timeoutSeconds=120") + ' -Djava.locale.providers=CLDR,SPI -XX:+UseZGC'
    $oldBat = "rem TURBOISM MANAGED BEGIN`r`nset `"TURBOISM_HOME=$homePath`"`r`nset `"JAVA_TOOL_OPTIONS=$oldOptions %JAVA_TOOL_OPTIONS%`"`r`nrem TURBOISM MANAGED END`r`n" + [Text.Encoding]::Default.GetString($originalBatBytes)
    [IO.File]::WriteAllText($bat, $oldBat, [Text.Encoding]::Default)
    $cached[0].Selected = $true
    Write-CubismInstallationState -StatePath $statePath -Candidates $cached
    foreach ($operation in @('-InitializeSelection', '-DisableShortcuts', '-IntegrateBat')) {
        & $powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $configurator -Home $homePath $operation -Elevated -InstallerDiscoverySnapshot $snapshot -InstallerDiscoverySnapshotSha256 $snapshotHash
        Assert-DiscoveryCache ($LASTEXITCODE -eq 0) "upgrade preserves the existing BAT integration through $operation"
    }
    $upgradedState = Read-CubismInstallationState -StatePath $statePath
    Assert-DiscoveryCache ($upgradedState.Valid -and $upgradedState.BatIntegrations.Count -eq 1 -and $upgradedState.BatIntegrations[0].OriginalSha256 -eq $integratedState.BatIntegrations[0].OriginalSha256) 'upgrade keeps the original BAT backup record'
    Restore-CubismBatIntegrations -Records $upgradedState.BatIntegrations
    Assert-DiscoveryCache ([Convert]::ToBase64String([IO.File]::ReadAllBytes($bat)) -ceq [Convert]::ToBase64String($originalBatBytes)) 'upgraded integration restores exact original bytes'

    $testShortcutDir = Join-Path $homePath 'test-shortcuts'
    foreach ($ver in @('5.0.00', '5.1.00', '5.1.03', '5.2.03', '5.3.02', '5.3.04')) {
        $modernName = "Turboism_Cubism_${ver}_Live2D_Cubism_${ver}-A1B2C3D4E5F6.lnk"
        $modernD3DName = "Turboism_Cubism_${ver}_Live2D_Cubism_${ver}-A1B2C3D4E5F6-D3D.lnk"
        $legacyName = "Turboism Cubism $ver [Live2D Cubism $ver-A1B2C3D4E5F6].lnk"
        Assert-DiscoveryCache (Test-CubismManagedShortcutPath -Path (Join-Path $testShortcutDir $modernName) -Directory $testShortcutDir) "managed shortcut path admits $ver modern"
        Assert-DiscoveryCache (Test-CubismManagedShortcutPath -Path (Join-Path $testShortcutDir $modernD3DName) -Directory $testShortcutDir) "managed shortcut path admits $ver modern D3D"
        Assert-DiscoveryCache (Test-CubismManagedShortcutPath -Path (Join-Path $testShortcutDir $legacyName) -Directory $testShortcutDir) "managed shortcut path admits $ver legacy"
    }

    [System.IO.File]::AppendAllText($jar, 'changed')
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash } 'changed host artifact fails closed'
    [System.IO.File]::WriteAllText($jar, 'synthetic reviewed artifact')
    [System.IO.File]::AppendAllText($java, 'changed')
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash } 'changed host Java fails closed'
    [System.IO.File]::WriteAllText($java, 'synthetic Java, not executable')
    [System.IO.File]::AppendAllText($agent, 'changed')
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash } 'different verifier payload cannot reuse the scan'
    [System.IO.File]::WriteAllText($agent, 'synthetic verifier, not executable')
    [System.IO.File]::AppendAllText($snapshot, ' ')
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash } 'snapshot byte tampering is rejected before parsing'
    [System.IO.File]::WriteAllBytes($snapshot, $snapshotBytes)
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 'bad' } 'missing or invalid snapshot digest is rejected'
    [System.IO.File]::WriteAllText($snapshot, '{')
    $malformedHash = Get-CubismSha256 $snapshot
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $malformedHash } 'malformed JSON with a matching digest is rejected'
    [System.IO.File]::WriteAllBytes($snapshot, $snapshotBytes)
    Remove-Item -LiteralPath $bat
    Assert-DiscoveryCacheThrows { Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $snapshot -ExpectedSha256 $snapshotHash } 'deleted target BAT is not silently rediscovered'

    $emptyReport = Join-Path $homePath 'empty.result'
    Write-CubismInstallerDiscoveryReport -TurboismHome $homePath -OutputPath $emptyReport -Roots @() | Out-Null
    $emptyHash = Get-CubismSha256 ($emptyReport + '.json')
    $empty = @(Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath ($emptyReport + '.json') -ExpectedSha256 $emptyHash)
    Assert-DiscoveryCache ($empty.Count -eq 0) 'empty successful scan has a valid framework-only snapshot'

    [System.IO.File]::WriteAllText($bat, "@echo off`r`nrem synthetic BAT, never executed`r`n")
    $script:CubismArtifactVersionResolver = {
        param($j, $a, $h)
        [System.IO.File]::AppendAllText($a, 'changed during probe')
        return '5.3.04'
    }
    $racing = New-CubismInstallationCandidate -Root $root -TurboismHome $homePath
    Assert-DiscoveryCache (-not $racing.Selectable) 'a host updated during the version probe is not admitted'
    [System.IO.File]::WriteAllText($jar, 'synthetic reviewed artifact')
    $script:CubismArtifactVersionResolver = {
        param($j, $a, $h)
        return [pscustomobject]@{
            Version='5.3.99'; CompatStatus='REJECTED'; Reason='base runtime capability did not resolve'
        }
    }
    $rejected = New-CubismInstallationCandidate -Root $root -TurboismHome $homePath
    Assert-DiscoveryCache (-not $rejected.Selectable -and $rejected.Status -eq 'Unsupported') 'coherent identity with failed base admission remains unselectable'
    Assert-DiscoveryCache ($rejected.Version -eq '5.3.99' -and $rejected.Reason -eq 'base runtime capability did not resolve') 'base admission failure preserves the declared version and real reason'
    $script:CubismArtifactVersionResolver = {
        param($j, $a, $h)
        return ConvertFrom-CubismHostProbeResult -JsonText $probeJson -ExitCode 0
    }
    $compatible = New-CubismInstallationCandidate -Root $root -TurboismHome $homePath
    Assert-DiscoveryCache ($compatible.Selectable -and $compatible.Status -ceq 'Compatible' -and $compatible.Version -ceq '5.3.99') 'unknown compatible version remains selectable after parsing and candidate discovery'
    $compatibleSnapshot = Join-Path $homePath 'compatible-snapshot.json'
    Write-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $compatibleSnapshot -Candidates @($compatible)
    $compatibleCached = @(Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $compatibleSnapshot -ExpectedSha256 (Get-CubismSha256 $compatibleSnapshot))
    Assert-DiscoveryCache ($compatibleCached.Count -eq 1 -and $compatibleCached[0].Status -ceq 'Compatible' -and $compatibleCached[0].Version -ceq '5.3.99' -and $compatibleCached[0].Selectable) 'snapshot reuse preserves unknown version and compatibility status'
    $script:CubismArtifactVersionResolver = { param($j, $a, $h) return '5.3.04' }
    $stable = New-CubismInstallationCandidate -Root $root -TurboismHome $homePath
    Assert-DiscoveryCache $stable.Selectable 'an unchanged exact candidate remains admissible'
    [System.IO.File]::AppendAllText($jar, 'changed after probe')
    $staleOutput = Join-Path $homePath 'stale-before-publish.json'
    Assert-DiscoveryCacheThrows { Write-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath $staleOutput -Candidates @($stable) } 'changed bytes cannot inherit an earlier version admission'
    Assert-DiscoveryCache (-not (Test-Path -LiteralPath $staleOutput)) 'stale candidate is rejected before snapshot publication'
    Write-Output "INSTALLER_DISCOVERY_CACHE_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
