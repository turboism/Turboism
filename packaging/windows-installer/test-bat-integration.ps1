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
    $original = "@echo off`r`nrem synthetic fixture, never executed`r`nexit /b 0`r`n"
    foreach ($name in @('plain', 'with spaces', '中文 home')) {
        $homePath = Join-Path $fixture $name
        [void][System.IO.Directory]::CreateDirectory($homePath)
        $text = Get-CubismBatIntegrationText -OriginalText $original -TurboismHome $homePath
        $lines = @($text -split '\r?\n')
        $expectedHome = 'set "TURBOISM_HOME=' + $homePath + '"'
        Assert-BatRegression (@($lines | Where-Object { $_ -ceq $expectedHome }).Count -eq 1) "one complete HOME assignment: $name"
        Assert-BatRegression (@($lines | Where-Object { $_ -match '^set "JAVA_TOOL_OPTIONS=.*-javaagent:.*%JAVA_TOOL_OPTIONS%"$' }).Count -eq 1) "one complete JVM assignment: $name"
        Assert-BatRegression (([regex]::Matches($text, '-javaagent:')).Count -eq 1) "one agent argument: $name"
        Assert-BatRegression ($text.EndsWith($original)) "original BAT body preserved: $name"
        Assert-BatRegression ((Get-CubismBatIntegrationText -OriginalText $text -TurboismHome $homePath) -ceq $text) "managed text idempotent: $name"
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
    Assert-BatThrows { Get-CubismBatIntegrationText -OriginalText $original -TurboismHome ($homePath + '&bad') } 'command metacharacter is rejected'
    Write-Output "BAT_INTEGRATION_REGRESSION_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
