# Windows-only isolated BAT regression; does not start Cubism or modify its installation.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cubism-launch-common.ps1')
$temp = Join-Path ([IO.Path]::GetTempPath()) ('turboism-proxy-launch-' + [guid]::NewGuid().ToString('N'))
$root = Join-Path $temp 'Cubism Fixture'
$homePath = Join-Path $temp 'Turboism Home'
$marker = Join-Path $temp 'result.txt'
$oldNoCwd = [Environment]::GetEnvironmentVariable('NoDefaultCurrentDirectoryInExePath', 'Process')
$oldMarker = [Environment]::GetEnvironmentVariable('TURBOISM_TEST_OUTPUT', 'Process')
try {
    [void][IO.Directory]::CreateDirectory((Join-Path $root 'app\jre\bin'))
    [void][IO.Directory]::CreateDirectory($homePath)
    $agent = Join-Path $homePath 'turboism-agent.jar'
    [IO.File]::WriteAllBytes($agent, [byte[]](1,2,3))
    $bat = Join-Path $root 'CubismEditor5.bat'
    Set-Content -LiteralPath $bat -Encoding ASCII -Value @(
        '@echo off', 'setlocal', 'cd /d "%~dp0"', 'set LIB_ROOT=%~dp0app\dll64',
        'if exist ProxyConfig.bat call ProxyConfig.bat', 'set JAVA_EXE=app\jre\bin\java.cmd',
        '%JAVA_EXE% ^', '  -showversion ^', '  com.live2d.cubism.CECubismEditorApp ^', '  "%~f1"', 'exit /b %ERRORLEVEL%'
    )
    Set-Content -LiteralPath (Join-Path $root 'ProxyConfig.bat') -Encoding ASCII -Value @(
        '@echo off', '>>"%TURBOISM_TEST_OUTPUT%" echo PROXY_RAN=1',
        '>>"%TURBOISM_TEST_OUTPUT%" echo DLL_ROOT=%LIB_ROOT%'
    )
    Set-Content -LiteralPath (Join-Path $root 'app\jre\bin\java.cmd') -Encoding ASCII -Value @(
        '@echo off', '>>"%TURBOISM_TEST_OUTPUT%" echo JAVA_RAN=1', 'exit /b 23'
    )
    $hash = (Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash
    $env:NoDefaultCurrentDirectoryInExePath = '1'
    $env:TURBOISM_TEST_OUTPUT = $marker
    $code = Invoke-CubismOfficialBat -OfficialBat $bat -CubismRoot $root -TurboismHome $homePath -Agent $agent
    $result = [IO.File]::ReadAllText($marker)
    if ($code -ne 23) { throw 'Official BAT exit status changed' }
    if (!$result.Contains('PROXY_RAN=1')) { throw 'Official proxy helper was not invoked' }
    if (!$result.Contains('DLL_ROOT=' + (Join-Path $root 'app\dll64'))) { throw 'Staged BAT changed original dp0 DLL path' }
    if ((Get-FileHash -LiteralPath $bat -Algorithm SHA256).Hash -ne $hash) { throw 'Original BAT was modified' }
    $global:LASTEXITCODE = 0
    Write-Host 'MANAGED_LAUNCH_PROXY_PASS: helper executed, DLL root preserved, original BAT unchanged, exit status preserved'
}
finally {
    [Environment]::SetEnvironmentVariable('NoDefaultCurrentDirectoryInExePath', $oldNoCwd, 'Process')
    [Environment]::SetEnvironmentVariable('TURBOISM_TEST_OUTPUT', $oldMarker, 'Process')
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
