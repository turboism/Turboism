[CmdletBinding()]
param(
    [string]$CubismRoot = 'F:/Live2D/Live2D Cubism 5.3.02',
    [string]$JdkRoot = 'C:/Program Files/Java/jdk-17',
    [string]$WorktreeId = 'short-wolverine',
    [string]$RunLabel = (Get-Date -Format 'yyyyMMdd-HHmmss')
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$run = Join-Path $repo "build/host-validation/plugin-hot-reload/$RunLabel"
if (Test-Path -LiteralPath $run) { throw "Run directory already exists: $run" }
New-Item -ItemType Directory -Path "$run/plugins", "$run/fixtures", "$run/probe-classes" -Force | Out-Null
$agent = Join-Path $repo "build/worktree/$WorktreeId/bootstrap/libs/turboism-agent.jar"
Copy-Item -LiteralPath $agent -Destination "$run/turboism-agent.jar"
$javac = Join-Path $JdkRoot 'bin/javac.exe'
$jar = Join-Path $JdkRoot 'bin/jar.exe'
$fixtureSource = Get-Content -LiteralPath "$PSScriptRoot/src/dev/turboism/validation/hotreload/FixturePlugin.java" -Raw
foreach ($version in @('0.1.0', '0.2.0', '0.3.0', '0.4.0')) {
    $classes = "$run/fixture-$version"
    New-Item -ItemType Directory -Path "$classes/META-INF/turboism/i18n" -Force | Out-Null
    [IO.File]::WriteAllText("$run/FixturePlugin.java", $fixtureSource.Replace('@VERSION@', $version))
    & $javac --release 17 -classpath $agent -d $classes "$run/FixturePlugin.java"
    if ($LASTEXITCODE) { throw 'Fixture compilation failed' }
    $descriptor = @{
        format='turboism.plugin.meta'; schemaVersion=3; id='dev.turboism.validation.hot-reload-fixture'
        name='Hot reload fixture'; version=$version; description='Validation-only generation fixture'
        entrypoints=@('dev.turboism.validation.hotreload.FixturePlugin'); turboismApi='[0.67.0,1.0.0)'
        authors=@(@{name='Test'}); license='MIT'; website='https://turboism.dev'; resources=@()
        i18n=@{baseName='META-INF/turboism/i18n/messages'; locales=@()}; dependencies=@(); permissions=@()
        capabilities=@(); environment=@{requiresCubism=$false; ui='swing'}; lifecycle=@{hotReload=$true}
        category='workflow'; tags=@('test')
    } | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText("$classes/META-INF/turboism/plugin.json", $descriptor)
    [IO.File]::WriteAllText("$classes/META-INF/turboism/i18n/messages.properties", '')
    & "$JdkRoot/bin/java.exe" "$PSScriptRoot/src/dev/turboism/validation/hotreload/PackFixture.java" $classes "$run/fixtures/$version.jar"
    if ($LASTEXITCODE) { throw 'Fixture packaging failed' }
}
Copy-Item -LiteralPath "$run/fixtures/0.1.0.jar" -Destination "$run/plugins/dev.turboism.validation.hot-reload-fixture.jar"
# HostProbe refers to FixturePlugin only for its class name; do not include fixture bytecode in the agent.
& $javac --release 17 -classpath "$agent;$run/fixture-0.1.0" -d "$run/probe-classes" "$PSScriptRoot/src/dev/turboism/validation/hotreload/HostProbe.java"
if ($LASTEXITCODE) { throw 'Probe compilation failed' }
[IO.File]::WriteAllText("$run/probe.mf", "Manifest-Version: 1.0`nPremain-Class: dev.turboism.validation.hotreload.HostProbe`n`n")
& $jar cfm "$run/probe.jar" "$run/probe.mf" -C "$run/probe-classes" .
if ($LASTEXITCODE) { throw 'Probe packaging failed' }
$config = Get-Content -LiteralPath "$repo/turboism.worktree.config.template.json" -Raw
[IO.File]::WriteAllText("$run/config.json", $config.Replace('${worktreeId}', $WorktreeId))
$java = Join-Path $CubismRoot 'app/jre/bin/java.exe'
$javaArgs = @(
    '-Xmx2g', '-classpath', 'app/lib/*;app/lib/jogl/*',
    '-Djava.library.path=app/dll64;app/dll64/windows-amd64', '-Djogamp.gluegen.UseTempJarCache=false',
    '-Dsun.java2d.d3d=false', '-Duser.language=zh', "-Dturboism.home=$run", '-Dturboism.graal.enabled=false',
    "-javaagent:$run/turboism-agent.jar=home=$run;timeoutSeconds=120", "-javaagent:$run/probe.jar=$run",
    'com.live2d.cubism.CECubismEditorApp'
)
$quoted = $javaArgs | ForEach-Object { '"' + $_ + '"' }
$process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $CubismRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/stdout.log" -RedirectStandardError "$run/stderr.log"
@{pid=$process.Id; runDirectory=$run; agentSha256=(Get-FileHash "$run/turboism-agent.jar").Hash; hostSha256=(Get-FileHash "$CubismRoot/app/lib/Live2D_Cubism.jar").Hash} | ConvertTo-Json | Set-Content "$run/launch.json"
Write-Output "Started pid=$($process.Id) evidence=$run"
