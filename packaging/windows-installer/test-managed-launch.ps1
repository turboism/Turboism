# Synthetic production-seam gate for the Windows managed launcher.
# It creates only temporary Cubism-shaped fixtures; it never discovers or starts
# an installed Cubism host.
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")

function Assert-ManagedLaunch {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    Write-Host "ok: $Message"
}

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-managed-launch-" + [guid]::NewGuid().ToString("N"))
$home = Join-Path $temp "Turboism home 空间"
$fixtureBase = Join-Path $temp "fixtures"
$marker = Join-Path $temp "official bat marker.txt"
New-Item -ItemType Directory -Path $home, $fixtureBase -Force | Out-Null

function New-SyntheticCubism {
    param([string]$Name, [string]$Version, [bool]$D3D = $false)
    $root = Join-Path $fixtureBase "$Name Cubism $Version"
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
    [System.IO.File]::WriteAllBytes((Join-Path $home "turboism-agent.jar"), [byte[]](9, 8, 7, 6))
    $root52 = New-SyntheticCubism -Name "Live2D" -Version "5.2"
    $root53 = New-SyntheticCubism -Name "Live2D" -Version "5.3" -D3D $true
    $root53DuplicateVersion = New-SyntheticCubism -Name "Live2D" -Version "5.3.02"
    $newSupported = New-SyntheticCubism -Name "Live2D" -Version "5.3.01"
    $unsupported = New-SyntheticCubism -Name "fixture unsupported" -Version "5.4.00"

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
    Write-CubismInstallationState -StatePath (Join-Path $home "cubism-installations.json") -Candidates $initial
    $statePath = Join-Path $home "cubism-installations.json"
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

    $shortcutDir = Join-Path $temp "Start Menu\Turboism"
    $managedShortcut = Join-Path $shortcutDir (Get-CubismShortcutName $candidates[0])
    Assert-ManagedLaunch ((Get-CubismShortcutName $candidates[1]) -ne (Get-CubismShortcutName $candidates[2])) "duplicate installations get distinct shortcut identities"
    $unrelatedShortcut = Join-Path $shortcutDir "Turboism Configurator.lnk"
    $outsideShortcut = Join-Path $temp "outside.lnk"
    New-Item -ItemType Directory -Path $shortcutDir -Force | Out-Null
    Set-Content -LiteralPath $managedShortcut -Value "managed" -Encoding ASCII
    Set-Content -LiteralPath $unrelatedShortcut -Value "configure" -Encoding ASCII
    Set-Content -LiteralPath $outsideShortcut -Value "outside" -Encoding ASCII
    $failedShortcutCleanup = @(Remove-CubismManagedShortcuts -Paths @($managedShortcut, $unrelatedShortcut, $outsideShortcut) -Directory $shortcutDir)
    Assert-ManagedLaunch ($failedShortcutCleanup.Count -eq 0) "owned shortcut cleanup reports no failure"
    Assert-ManagedLaunch (-not (Test-Path -LiteralPath $managedShortcut)) "stale managed shortcut is removed"
    Assert-ManagedLaunch ((Test-Path -LiteralPath $unrelatedShortcut) -and (Test-Path -LiteralPath $outsideShortcut)) "shortcut cleanup is confined to owned entries"

    $comShortcut = $null
    try {
        $comShortcut = New-CubismManagedShortcut -Home $home -Candidate $candidates[0] -Variant "normal" -ShortcutDirectory $shortcutDir
        Assert-ManagedLaunch (Test-Path -LiteralPath $comShortcut -PathType Leaf) "COM creates a real managed .lnk in the bounded directory"
        Assert-ManagedLaunch (@(Remove-CubismManagedShortcuts -Paths @($comShortcut) -Directory $shortcutDir).Count -eq 0) "COM-created shortcut is removed through the same ownership guard"
    }
    catch {
        if ($env:OS -eq "Windows_NT") { throw }
        Write-Host "ok: COM shortcut workflow not available on this host"
    }

    $bat = Join-Path $root53 "CubismEditor5.bat"
    $d3dBat = Join-Path $root53 "CubismEditor5_D3D.bat"
    $agent = Join-Path $home "turboism-agent.jar"
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
        $exitCode = Invoke-CubismOfficialBat -OfficialBat $bat -CubismRoot $root53 -Home $home -Agent $agent -Arguments @("fixture argument")
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
        try { [void](Invoke-CubismOfficialBat -OfficialBat $bat -CubismRoot $root53 -Home $home -Agent $agent) } catch { $malformedThrew = $true }
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

    $ps = Join-Path $PSHOME "powershell.exe"
    $launcher = Join-Path $scriptDir "launch-cubism-turboism.ps1"
    $launchArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -CubismRoot "{2}" -Variant d3d' -f $launcher, $home, $root53
    $process = Start-Process -FilePath $ps -ArgumentList $launchArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($process.ExitCode -eq 23) "launcher selects and invokes the explicit D3D BAT"
    Assert-ManagedLaunch ((Get-Content -LiteralPath $marker -Raw) -match 'D3D=1') "D3D launcher entry is distinct"

    $genericArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -ProbeOnly' -f $launcher, $home
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

    $badArgs = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -CubismRoot "{2}" -ProbeOnly' -f $launcher, $home, $unsupported
    $badProcess = Start-Process -FilePath $ps -ArgumentList $badArgs -Wait -PassThru -NoNewWindow
    Assert-ManagedLaunch ($badProcess.ExitCode -ne 0) "explicit unsupported root is rejected before launch"
    Write-Host "MANAGED_LAUNCH_TEST=PASS"
}
finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item Env:TURBOISM_TEST_OUTPUT -ErrorAction SilentlyContinue
}
