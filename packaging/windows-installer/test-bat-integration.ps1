# Executable regression: no Cubism, registry, shortcuts, elevation or Java required.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')

$script:Assertions = 0
function Assert-BatRegression {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "BAT regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}
function Assert-BatThrows {
    param([scriptblock]$Action, [string]$Message)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-BatRegression $rejected $Message
}

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('turboism-bat-regression-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($fixture)
try {
    # Exercise the launcher's actual parameter binder without launching Cubism.
    $launcherPath = Join-Path $PSScriptRoot 'launch-cubism-turboism.ps1'
    $tokens = $null
    $parseErrors = $null
    $launcherAst = [System.Management.Automation.Language.Parser]::ParseFile($launcherPath, [ref]$tokens, [ref]$parseErrors)
    Assert-BatRegression ($parseErrors.Count -eq 0) 'managed launcher parses'
    $attributes = [string]::Join("`n", @($launcherAst.ParamBlock.Attributes | ForEach-Object { $_.Extent.Text }))
    $binder = [scriptblock]::Create($attributes + "`n" + $launcherAst.ParamBlock.Extent.Text + @'

[pscustomobject]@{ HomePath = $HomePath; CubismRoot = $CubismRoot; CubismJava = $CubismJava; ProjectPath = $ProjectPath; Files = @($CubismArguments) }
'@)
    $model = 'C:\Models\中文 model.cmo3'
    $bound = & $binder -HomePath 'C:\Turboism' -CubismRoot 'C:\Cubism' -Variant normal $model
    Assert-BatRegression ($bound.CubismJava -eq '' -and $bound.ProjectPath -eq '' -and $bound.Files.Count -eq 1 -and $bound.Files[0] -eq $model) 'official BAT forwards a bare model path as a file, not a Java override'
    $bound = & $binder $model
    Assert-BatRegression ($bound.HomePath -eq '' -and $bound.Files.Count -eq 1 -and $bound.Files[0] -eq $model) 'generic launcher preserves a bare model path'
    $bound = & $binder -ProjectPath $model -CubismJava 'C:\Graal\bin\java.exe'
    Assert-BatRegression ($bound.ProjectPath -eq $model -and $bound.CubismJava -eq 'C:\Graal\bin\java.exe' -and $bound.Files.Count -eq 0) 'explicit project and Java options still bind by name'

    $original = "@echo off`r`nrem synthetic fixture, never executed`r`nexit /b 0`r`n"
    $syntheticRoot = Join-Path $fixture 'synthetic options host'
    [void][System.IO.Directory]::CreateDirectory($syntheticRoot)
    $syntheticBat = Join-Path $syntheticRoot 'CubismEditor5.bat'
    $syntheticBody = "@echo off`r`ncd /d `"$syntheticRoot`"`r`n%JAVA_EXE% -showversion ^`r`n  com.live2d.cubism.CECubismEditorApp ^`r`n  %*`r`n"
    [System.IO.File]::WriteAllText($syntheticBat, $syntheticBody)
    $syntheticHash = Get-CubismSha256 $syntheticBat
    foreach ($name in @('plain', 'with spaces', '中文 home')) {
        $homePath = Join-Path $fixture $name
        [void][System.IO.Directory]::CreateDirectory($homePath)
        $syntheticAgent = Join-Path $homePath 'turboism-agent.jar'
        [System.IO.File]::WriteAllText($syntheticAgent, 'synthetic normal file; never executed')
        $managedBat = New-CubismManagedOptionsBat -OfficialBat $syntheticBat -CubismRoot $syntheticRoot -TurboismHome $homePath -Agent $syntheticAgent
        $managedText = [System.IO.File]::ReadAllText($managedBat)
        Assert-BatRegression (([regex]::Matches($managedText, '-XX:\+DisableAttachMechanism')).Count -eq 1) "ephemeral managed BAT emits default ownership option once: $name"
        Assert-BatRegression ((Get-CubismSha256 $syntheticBat) -eq $syntheticHash) "managed staging preserves synthetic official BAT bytes: $name"
        $text = Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath
        $lines = @($text -split '\r?\n')
        $expectedHome = 'set "TURBOISM_HOME=' + $homePath + '"'
        Assert-BatRegression (@($lines | Where-Object { $_ -ceq $expectedHome }).Count -eq 1) "one complete HOME assignment: $name"
        Assert-BatRegression ($text.Contains('powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $homePath + '\launch-cubism-turboism.ps1"')) "official entry delegates to the managed launcher: $name"
        Assert-BatRegression ($text.Contains('-CubismRoot "%~dp0." -Variant normal %*')) "official entry preserves host root and file arguments: $name"
        Assert-BatRegression ($text.Contains('exit /b %errorlevel%')) "official entry stops before the preserved original body: $name"
        Assert-BatRegression ($text -notmatch 'JAVA_TOOL_OPTIONS|DisableAttachMechanism|UseZGC|optimization\.') "integration contains no frozen JVM preferences: $name"
        Assert-BatRegression ($text.EndsWith($original)) "original BAT body preserved: $name"
        Assert-BatRegression ((Get-CubismBatIntegrationText -OriginalText $text -TurboismHome $homePath) -ceq $text) "managed text idempotent: $name"
        $d3dText = Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath -Variant d3d
        Assert-BatRegression ($d3dText.Contains('-Variant d3d %*')) "D3D entry preserves renderer selection: $name"
        [System.IO.File]::WriteAllText((Join-Path $homePath 'config.json'), '{"meshTriangulationEdgeIndex":false}')
        $offText = Get-CubismBatIntegrationText -OriginalText $text -TurboismHome $homePath
        Assert-BatRegression ($offText -ceq $text) "settings edits need no regenerated integration: $name"
        Assert-BatRegression ($offText.EndsWith($original)) "off integration preserves the original BAT body: $name"
        $offBat = New-CubismManagedOptionsBat -OfficialBat $syntheticBat -CubismRoot $syntheticRoot -TurboismHome $homePath -Agent $syntheticAgent
        Assert-BatRegression ([System.IO.File]::ReadAllText($offBat) -notmatch 'DisableAttachMechanism') "managed restart reads the saved off preference: $name"
    }

    # Replay an unchanged integrated entry across config edits. The launcher's
    # real staging path must remove delegation and consume the latest preferences.
    [System.IO.File]::WriteAllText($syntheticBat, (Get-CubismBatIntegrationText -OriginalText $syntheticBody -TurboismHome $homePath))
    foreach ($enabled in @($false, $true, $false)) {
        $config = @{
            meshTriangulationEdgeIndex = $enabled
            launcher = @{ zgc = $enabled; uniformLocationCache = $enabled; modelUpdateSkip = $enabled; uploadElision = $enabled; incrementalUpdate = $enabled; memoryProfile = $(if ($enabled) { 'balanced4g' } else { 'system' }) }
        } | ConvertTo-Json -Depth 4
        [System.IO.File]::WriteAllText((Join-Path $homePath 'config.json'), $config)
        $nextBat = New-CubismManagedOptionsBat -OfficialBat $syntheticBat -CubismRoot $syntheticRoot -TurboismHome $homePath -Agent $syntheticAgent
        $nextText = [System.IO.File]::ReadAllText($nextBat)
        Assert-BatRegression ($nextText -notmatch 'launch-cubism-turboism\.ps1|TURBOISM MANAGED BEGIN') "staging removes delegation without recursion: $enabled"
        foreach ($token in @('-XX:+DisableAttachMechanism', '-XX:+UseZGC', '-Xmx4g', '-Dturboism.optimization.incrementalUpdate=true')) {
            Assert-BatRegression ($nextText.Contains($token) -eq $enabled) "next launch reads current preference $token : $enabled"
        }
        foreach ($name in @('uniformLocationCache', 'modelUpdateSkip', 'uploadElision')) {
            Assert-BatRegression ($nextText.Contains("-Dturboism.optimization.$name=false") -ne $enabled) "next launch reads current $name : $enabled"
        }
    }

    $homePath = Join-Path $fixture 'upgrade home'
    [void][System.IO.Directory]::CreateDirectory($homePath)
    $root = Join-Path $fixture 'synthetic-host'
    [void][System.IO.Directory]::CreateDirectory($root)
    $bat = Join-Path $root 'CubismEditor5.bat'
    $encoding = [System.Text.Encoding]::Default
    [System.IO.File]::WriteAllText($bat, $original, $encoding)
    $originalHash = Get-CubismSha256 $bat
    $candidate = [pscustomobject]@{ Selected = $true; Selectable = $true; OfficialBat = $bat; D3DBat = $null }
    $records = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate))
    Assert-BatRegression ($records.Count -eq 1) 'fresh integration records exactly one BAT'
    Assert-BatRegression ((Get-CubismSha256 $records[0].BackupPath) -eq $originalHash) 'backup preserves exact original bytes'
    $managedHash = Get-CubismSha256 $bat
    $again = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) -ExistingRecords $records)
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $managedHash -and $again[0].ManagedSha256 -eq $managedHash) 'reinstall does not rewrite the current BAT'

    # State can be absent after a reinstall even though BAT + backup survived.
    $recovered = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate))
    Assert-BatRegression ($recovered.Count -eq 1 -and $recovered[0].OriginalSha256 -eq $originalHash -and $recovered[0].ManagedSha256 -eq $managedHash) 'lost state is recovered only from an exact known managed BAT and its backup'
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $managedHash -and (Get-CubismSha256 $records[0].BackupPath) -eq $originalHash) 'state recovery preserves both existing files'
    [System.IO.File]::AppendAllText($bat, "rem unknown edit after state loss`r`n", $encoding)
    $untrackedEditHash = Get-CubismSha256 $bat
    Assert-BatThrows { Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) } 'lost state never permits overwriting unknown BAT edits'
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $untrackedEditHash -and (Get-CubismSha256 $records[0].BackupPath) -eq $originalHash) 'rejected state recovery preserves BAT and backup'
    [System.IO.File]::WriteAllText($bat, (Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath), $encoding)

    # Previous releases froze these options in the official BAT. State may
    # contain only installations/managedShortcuts, as in the reported upgrade.
    $legacyOptions = (ConvertTo-JdkOptionToken "-Dturboism.home=$homePath") + ' ' + (ConvertTo-JdkOptionToken "-javaagent:$(Join-Path $homePath 'turboism-agent.jar')=home=$homePath;timeoutSeconds=120") + ' -Djava.locale.providers=CLDR,SPI -XX:+UseZGC'
    $legacyManaged = "rem TURBOISM MANAGED BEGIN`r`nset `"TURBOISM_HOME=$homePath`"`r`nset `"JAVA_TOOL_OPTIONS=$legacyOptions %JAVA_TOOL_OPTIONS%`"`r`nrem TURBOISM MANAGED END`r`n" + $original
    [System.IO.File]::WriteAllText($bat, $legacyManaged, $encoding)
    $legacyRecovered = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate))
    Assert-BatRegression ($legacyRecovered.Count -eq 1 -and $legacyRecovered[0].OriginalSha256 -eq $originalHash) 'old JAVA_TOOL_OPTIONS integration with missing state reuses original backup'
    Assert-BatRegression ([IO.File]::ReadAllText($bat, $encoding) -ceq (Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath)) 'legacy integration upgrades to current launcher delegation'
    [IO.File]::WriteAllText($bat, $legacyManaged.Replace('-XX:+UseZGC', '-XX:+UseZGC & echo unexpected'), $encoding)
    Assert-BatThrows { Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) } 'unknown commands inside old managed markers are rejected'
    [IO.File]::WriteAllText($bat, $legacyManaged + 'rem unknown change', $encoding)
    Assert-BatThrows { Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) } 'legacy body must match the surviving original backup'
    [IO.File]::WriteAllText($bat, $legacyManaged, $encoding)
    [IO.File]::Delete($records[0].BackupPath)
    $legacyRecovered = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate))
    Assert-BatRegression ((Get-CubismSha256 $legacyRecovered[0].BackupPath) -eq $originalHash) 'recognized legacy integration without a backup preserves the original body before migration'

    # Reproduce the old generator output with a valid ownership record and backup.
    $broken = "rem TURBOISM MANAGED BEGIN`r`nset `"TURBOISM_HOME=`r`n$homePath`r`n`"`r`nset `"JAVA_TOOL_OPTIONS=`r`n-javaagent:broken`r`n`"`r`nrem TURBOISM MANAGED END`r`n" + $original
    [System.IO.File]::WriteAllText($bat, $broken, $encoding)
    $oldRecord = [pscustomobject]@{
        Path = $bat; BackupPath = $records[0].BackupPath
        OriginalSha256 = $originalHash; ManagedSha256 = (Get-CubismSha256 $bat)
    }
    $repaired = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) -ExistingRecords @($oldRecord))
    $actual = [System.IO.File]::ReadAllText($bat, $encoding)
    Assert-BatRegression ($actual -ceq (Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath)) 'tracked malformed old BAT is repaired from original backup'
    Restore-CubismBatIntegrations -Records $repaired
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $originalHash) 'uninstall restores exact original BAT bytes'
    Assert-BatRegression (-not (Test-Path -LiteralPath ($bat + '.turboism-original.bak'))) 'successful restoration removes only its backup'

    $records = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate))
    [System.IO.File]::AppendAllText($bat, "rem user edit`r`n", $encoding)
    $editedHash = Get-CubismSha256 $bat
    Assert-BatThrows { Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) -ExistingRecords $records } 'user edits reject automatic overwrite'
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $editedHash) 'rejected upgrade preserves user edit'
    Assert-BatThrows { Restore-CubismBatIntegrations -Records $records } 'conflicting BAT refuses restoration'
    Assert-BatRegression (Test-Path -LiteralPath $records[0].BackupPath) 'conflict retains recovery backup'

    # Cubism restored its own BAT and the backup vanished: the record is stale.
    $staleRecords = $records
    [System.IO.File]::WriteAllText($bat, $original, $encoding)
    Remove-Item -LiteralPath $staleRecords[0].BackupPath -Force
    $healed = @(Invoke-CubismBatIntegration -TurboismHome $homePath -Candidates @($candidate) -ExistingRecords $staleRecords)
    Assert-BatRegression ($healed.Count -eq 1 -and $healed[0].OriginalSha256 -eq $originalHash -and (Test-Path -LiteralPath $healed[0].BackupPath)) 'stale record with restored BAT and missing backup re-integrates'
    Restore-CubismBatIntegrations -Records $healed
    [System.IO.File]::WriteAllText($bat, $original, $encoding)
    Restore-CubismBatIntegrations -Records $staleRecords
    Assert-BatRegression ((Get-CubismSha256 $bat) -eq $originalHash) 'stale record restoration is a no-op that does not throw'
    Assert-BatThrows { Get-CubismBatIntegrationText -OriginalText $original -TurboismHome ($homePath + '&bad') } 'command metacharacter is rejected'
    Write-Output "BAT_INTEGRATION_REGRESSION_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
