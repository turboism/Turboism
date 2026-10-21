# Manual import exercises the same report and snapshot consumed by NSIS.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
$script:Assertions = 0
function Assert-Import {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Cubism import regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}
function New-ImportFixture {
    param([string]$Root)
    foreach ($directory in @('app/lib', 'app/jre/bin')) { [void][IO.Directory]::CreateDirectory((Join-Path $Root $directory)) }
    foreach ($file in @('CubismEditor5.bat', 'app/lib/Live2D_Cubism.jar', 'app/jre/bin/java.exe')) {
        [IO.File]::WriteAllText((Join-Path $Root $file), 'synthetic arbitrary contents; no known hash')
    }
    return $Root
}
function Read-ImportReport {
    param([string]$Path)
    return @([IO.File]::ReadAllLines($Path, [Text.Encoding]::Unicode))
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('turboism-import-' + [guid]::NewGuid().ToString('N'))
$previousCulture = [Threading.Thread]::CurrentThread.CurrentUICulture
try {
    $homePath = Join-Path $fixture 'Turboism home'
    [void][IO.Directory]::CreateDirectory($homePath)
    [IO.File]::WriteAllText((Join-Path $homePath 'turboism-agent.jar'), 'synthetic verifier')
    $first = New-ImportFixture (Join-Path $fixture 'custom/用户目录/renamed installation')
    $second = New-ImportFixture (Join-Path $fixture 'another deep tree/renamed editor')
    $unknown = New-ImportFixture (Join-Path $fixture 'unknown version')
    $script:CubismArtifactVersionResolver = { param($j, $a, $h) return '5.3.04' }
    $initial = Join-Path $homePath 'initial.result'
    Write-CubismInstallerDiscoveryReport -TurboismHome $homePath -OutputPath $initial -Roots @($first) | Out-Null
    function Get-CubismDiscoveryRoots { throw 'manual import must not rescan the disks' }
    $imported = Join-Path $homePath 'imported.result'
    Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $imported -Root $second -PreviousSnapshot ($initial + '.json') -PreviousSnapshotSha256 (Get-CubismSha256 ($initial + '.json')) | Out-Null
    $rows = Read-ImportReport $imported
    Assert-Import ($rows -contains 'RESULT|OK|2|0') 'valid renamed installation is appended without broad discovery'
    $cached = @(Read-CubismInstallerSnapshot -TurboismHome $homePath -SnapshotPath ($imported + '.json') -ExpectedSha256 (Get-CubismSha256 ($imported + '.json')))
    Assert-Import ($cached.Count -eq 2 -and $cached[1].Version -eq '5.3.04') 'import reaches the installation handoff with an exact version'

    $duplicate = Join-Path $homePath 'duplicate.result'
    Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $duplicate -Root $second -PreviousSnapshot ($imported + '.json') -PreviousSnapshotSha256 (Get-CubismSha256 ($imported + '.json')) | Out-Null
    Assert-Import ((Read-ImportReport $duplicate) -contains 'RESULT|OK|2|0') 'reimporting the same folder does not duplicate it'
    $oldBytes = [Convert]::ToBase64String([IO.File]::ReadAllBytes($duplicate + '.json'))
    foreach ($missing in @('CubismEditor5.bat', 'app/jre/bin/java.exe', 'app/lib/Live2D_Cubism.jar')) {
        $incomplete = New-ImportFixture (Join-Path $fixture ('incomplete-' + [guid]::NewGuid().ToString('N')))
        Remove-Item -LiteralPath (Join-Path $incomplete $missing)
        $rejected = Join-Path $homePath ([guid]::NewGuid().ToString('N') + '.result')
        Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $rejected -Root $incomplete -PreviousSnapshot ($duplicate + '.json') -PreviousSnapshotSha256 (Get-CubismSha256 ($duplicate + '.json')) | Out-Null
        Assert-Import ((Read-ImportReport $rejected) -contains 'RESULT|ERROR|0|0|InvalidCubismRoot') "missing $missing produces the localized invalid-folder signal"
        Assert-Import (-not (Test-Path -LiteralPath ($rejected + '.json'))) 'invalid import never publishes a replacement snapshot'
    }
    Assert-Import ($oldBytes -ceq [Convert]::ToBase64String([IO.File]::ReadAllBytes($duplicate + '.json'))) 'failed imports keep previous results unchanged'

    # Layout is enough to show the installation; version/compatibility admission
    # is separate and must never turn a real folder into an invalid-folder error.
    $script:CubismArtifactVersionResolver = { return $null }
    $unsupported = Join-Path $homePath 'unsupported.result'
    Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $unsupported -Root $unknown -PreviousSnapshot ($duplicate + '.json') -PreviousSnapshotSha256 (Get-CubismSha256 ($duplicate + '.json')) | Out-Null
    Assert-Import ((Read-ImportReport $unsupported) -contains 'RESULT|OK|2|1') 'structurally valid installation with unknown identity is displayed as unsupported'
    $script:CubismArtifactVersionResolver = { return '5.3.04' }
    $again = Join-Path $homePath 'again.result'
    Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $again -Root $second -PreviousSnapshot ($unsupported + '.json') -PreviousSnapshotSha256 (Get-CubismSha256 ($unsupported + '.json')) | Out-Null
    $rows = Read-ImportReport $again
    Assert-Import ($rows -contains 'RESULT|OK|2|1') 'later imports preserve existing unsupported rows'
    Assert-Import (@($rows | Where-Object { $_ -like 'DISPLAY|*' }).Count -eq 3) 'report contains each supported and unsupported folder exactly once'
    foreach ($locale in @('en-US', 'zh-CN', 'ja-JP', 'ko-KR')) {
        [Threading.Thread]::CurrentThread.CurrentUICulture = [Globalization.CultureInfo]::GetCultureInfo($locale)
        $localized = Join-Path $homePath ($locale + '.result')
        Write-CubismInstallerImportReport -TurboismHome $homePath -OutputPath $localized -Root $second | Out-Null
        $label = switch ($locale) { 'en-US' {'Detected'} 'zh-CN' {'已识别'} 'ja-JP' {'検出'} 'ko-KR' {'감지됨'} }
        Assert-Import (@((Read-ImportReport $localized) | Where-Object { $_.StartsWith("DISPLAY|[$label 5.3.04] ") }).Count -eq 1) "$locale import result uses its localized status"
    }
    # Run the real wrapper and worker to verify Unicode requests, culture and
    # snapshot arguments survive process boundaries. Synthetic Java is not executable.
    $request = Join-Path $homePath 'selected-folder.root'
    [IO.File]::WriteAllBytes($request, [Text.Encoding]::Unicode.GetBytes($unknown))
    $workerReport = Join-Path $homePath 'worker.result'
    $powershell = (Get-Process -Id $PID).Path
    & $powershell -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'configure_turboism.ps1') -Home $homePath -InstallerDiscoveryOutput $workerReport -InstallerImportRequest $request -InstallerDiscoverySnapshot ($again + '.json') -InstallerDiscoverySnapshotSha256 (Get-CubismSha256 ($again + '.json')) -InstallerLanguage 1042
    Assert-Import ($LASTEXITCODE -eq 0) 'configurator wrapper and worker complete the import'
    $rows = Read-ImportReport $workerReport
    Assert-Import ($rows -contains 'RESULT|OK|2|1') 'worker preserves previous installations and imports the Unicode request'
    Assert-Import (@($rows | Where-Object { $_.StartsWith('DISPLAY|[미지원] ') }).Count -eq 1) 'worker uses the selected installer language for unsupported status'
    Write-Output "INSTALLER_IMPORT_PASS assertions=$script:Assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    [Threading.Thread]::CurrentThread.CurrentUICulture = $previousCulture
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
