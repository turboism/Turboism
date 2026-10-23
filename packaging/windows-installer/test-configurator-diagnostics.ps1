# Runs the real headless helper without UAC or an installed Cubism host.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('turboism-diagnostics-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
try {
    $helper = Join-Path $PSScriptRoot 'configure_turboism.ps1'
    $powershell = (Get-Process -Id $PID).Path
    [IO.File]::WriteAllText((Join-Path $fixture 'cubism-installations.json'), '{broken json')
    $log = Join-Path $fixture 'logs/installer/configure-turboism.log'
    foreach ($mode in @('IntegrateBat', 'DisableBat', 'Cleanup')) {
        # -Elevated enters the exact child code path, without invoking RunAs.
        $output = & $powershell -NoProfile -NonInteractive -File $helper -HomePath $fixture "-$mode" -Elevated 2>&1
        if ($LASTEXITCODE -eq 0) { throw "$mode unexpectedly succeeded" }
        $text = [IO.File]::ReadAllText($log)
        if ($text -notmatch ('CONFIGURATOR_FAILED mode=' + $mode + ' elevated=True') -or
            $text -notmatch 'Managed installation state is invalid' -or
            $text -notmatch 'line=' -or $text -notmatch 'stack=') {
            throw "$mode failed without persistent diagnostic context. Log: $text"
        }
        Write-Output "PASS $mode persists the elevated failure, location and stack"
    }
    [IO.File]::Delete((Join-Path $fixture 'cubism-installations.json'))
    $output = & $powershell -NoProfile -NonInteractive -File $helper -HomePath $fixture -DisableBat 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Empty DisableBat should succeed without UAC: $output" }
    Write-Output 'PASS empty DisableBat still succeeds without UAC'

    # Exercise the production RunAs boundary with a fake process, never UAC.
    $tokens = $null; $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'Configurator parse failed' }
    foreach ($name in @('Write-InstallerLog', 'Invoke-ElevatedConfiguratorMode')) {
        $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }.GetNewClosure(), $true)
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $installerLogPath = $log
    $scriptPath = $helper
    $turboismHome = $fixture
    $InstallerDiscoverySnapshot = ''; $InstallerDiscoverySnapshotSha256 = ''
    function Start-Process {
        param($FilePath, $ArgumentList, $Verb, $WindowStyle, [switch]$Wait, [switch]$PassThru)
        if ($Verb -ne 'RunAs' -or -not $Wait -or -not $PassThru) { throw 'Incorrect elevation launch' }
        if ($WindowStyle -ne 'Hidden') { throw 'Headless elevation must not open an interactive console window' }
        if ($script:FakeLaunchFailure) { throw [ComponentModel.Win32Exception]::new(1223) }
        $process = [pscustomobject]@{ ExitCode = $script:FakeExitCode; Id = 1234 }
        $process | Add-Member -MemberType ScriptMethod -Name Dispose -Value { $script:ProcessDisposed = $true }
        return $process
    }
    $script:FakeLaunchFailure = $false
    foreach ($code in @(0, 37)) {
        $script:FakeExitCode = $code; $script:ProcessDisposed = $false
        $result = Invoke-ElevatedConfiguratorMode -Mode IntegrateBat 2>$null
        if ($result -ne $code -or -not $script:ProcessDisposed) { throw 'Elevated exit code was lost' }
        if ([IO.File]::ReadAllText($log) -notmatch ("ELEVATION_EXIT mode=IntegrateBat childPid=1234 exitCode=$code")) { throw 'Missing child exit diagnostic' }
        Write-Output "PASS elevation preserves and logs exit code $code"
    }
    foreach ($launchFailure in @($false, $true)) {
        $script:FakeExitCode = $null; $script:FakeLaunchFailure = $launchFailure
        $caught = $false
        try { Invoke-ElevatedConfiguratorMode -Mode IntegrateBat | Out-Null } catch { $caught = $true }
        if (-not $caught) { throw 'Elevation failure was treated as success' }
    }
    $text = [IO.File]::ReadAllText($log)
    if ($text -notmatch 'ELEVATION_FAILED' -or $text -notmatch 'returned no exit code' -or $text -notmatch 'Win32Exception') { throw 'Elevation failure details were lost' }
    Write-Output 'PASS launch cancellation and missing exit code retain diagnostic details'

}
finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
