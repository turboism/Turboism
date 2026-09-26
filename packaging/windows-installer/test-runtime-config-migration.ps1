# Executable regression for the installer-side runtime config migration
# (-ApplyInstallerSelection): the migration must accept every section the
# runtime itself persists (hooks.startup, textureAtlas, ...) and still fail
# closed on unknown or mistyped fields. No Cubism, registry or elevation.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$script = Join-Path $scriptDir 'configure_turboism.ps1'
$assertions = 0

function New-MigrationHome {
    $home_ = Join-Path ([System.IO.Path]::GetTempPath()) ('tis-cfg-migration-' + [guid]::NewGuid().ToString('N'))
    [void][System.IO.Directory]::CreateDirectory($home_)
    return $home_
}

function Invoke-Migration {
    param([string]$HomePath, [string]$DisabledIds = "")
    $args_ = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', $script, '-Home', $HomePath,
        '-ApplyInstallerSelection',
        '-BundledPluginIds', 'dev.turboism.plugin.texture-atlas;dev.turboism.plugin.clipmask-viewer'
    )
    if ($DisabledIds -ne '') { $args_ += @('-DisabledPluginIds', $DisabledIds) }
    $output = & pwsh @args_ 2>&1
    return @{ ExitCode = $LASTEXITCODE; Output = (@($output) -join "`n") }
}

function Assert-Migration {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:assertions++
    Write-Output "PASS $Message"
}

try {
    # 1. Fresh install: no config.json -> a valid v1 document is created.
    $home1 = New-MigrationHome
    $result = Invoke-Migration -HomePath $home1
    Assert-Migration ($result.ExitCode -eq 0) 'fresh install creates config.json'
    $created = Get-Content -LiteralPath (Join-Path $home1 'config.json') -Raw | ConvertFrom-Json
    Assert-Migration ($created.format -ceq 'turboism.runtime.config' -and $created.schemaVersion -eq 1) 'created config is v1 runtime config'

    # 2. Regression: every section the runtime itself persists must be accepted
    #    and preserved (the Editor writes hooks.startup and textureAtlas).
    $home2 = New-MigrationHome
    $runtimeWritten = @'
{
  "format": "turboism.runtime.config",
  "schemaVersion": 1,
  "worktreeId": "turboism-runtime",
  "pluginDirs": ["plugins"],
  "logLevel": "INFO",
  "locale": "zh-Hans",
  "hooks": { "startup": { "skipUpdateCheck": true, "skipSplash": true, "skipInformation": true } },
  "launcher": { "cubismJvm": "graalvm", "zgc": true },
  "textureAtlas": { "algorithmId": "maxrects-bssf", "parallel": true }
}
'@
    [System.IO.File]::WriteAllText((Join-Path $home2 'config.json'), $runtimeWritten, [System.Text.UTF8Encoding]::new($false))
    $result = Invoke-Migration -HomePath $home2 -DisabledIds 'dev.turboism.plugin.clipmask-viewer'
    Assert-Migration ($result.ExitCode -eq 0) 'runtime-persisted sections (hooks.startup, textureAtlas) are accepted'
    $migrated = Get-Content -LiteralPath (Join-Path $home2 'config.json') -Raw | ConvertFrom-Json
    Assert-Migration ($migrated.textureAtlas.algorithmId -ceq 'maxrects-bssf' -and $migrated.textureAtlas.parallel) 'textureAtlas selection survives migration'
    Assert-Migration ($migrated.disabledPlugins -contains 'dev.turboism.plugin.clipmask-viewer') 'requested disabled id is merged'

    # 3. Unknown top-level fields still fail closed without writing payload.
    $home3 = New-MigrationHome
    [System.IO.File]::WriteAllText((Join-Path $home3 'config.json'), '{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-runtime","futureField":true}', [System.Text.UTF8Encoding]::new($false))
    $result = Invoke-Migration -HomePath $home3
    Assert-Migration ($result.ExitCode -ne 0 -and $result.Output -like '*CONFIG_SELECTION_FAILED*') 'unknown top-level field fails closed'

    # 4. Mistyped textureAtlas fields fail closed.
    $home4 = New-MigrationHome
    [System.IO.File]::WriteAllText((Join-Path $home4 'config.json'), '{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-runtime","textureAtlas":{"parallel":"yes"}}', [System.Text.UTF8Encoding]::new($false))
    $result = Invoke-Migration -HomePath $home4
    Assert-Migration ($result.ExitCode -ne 0) 'mistyped textureAtlas.parallel fails closed'

    Write-Output "RUNTIME_CONFIG_MIGRATION_PASS assertions=$assertions PowerShell=$($PSVersionTable.PSVersion)"
}
finally {
    # Fixture homes are cleaned per-case via the OS temp cleaner; nothing else to do.
}
