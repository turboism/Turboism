[CmdletBinding()]
param(
    [string]$CubismRoot = "",
    [string]$ProjectPath = "",
    [ValidateSet("matrix", "statistics-read", "binding-read", "binding-matrix", "parameter-menu-smoke", "persist-write", "persist-read", "plugin-scope-close", "document-close")]
    [string]$ValidationMode = "matrix",
    [int]$TimeoutSeconds = 300,
    [switch]$ProbeOnly,
    [switch]$ProbeAgent
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-CubismRoot {
    param([string]$Requested)

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $candidate = $Requested
        if ([System.IO.Path]::GetExtension($candidate) -ieq ".bat") {
            $candidate = Split-Path -Parent $candidate
        }
        if (Test-Path -LiteralPath (Join-Path $candidate "CubismEditor5.bat") -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        throw "Official CubismEditor5.bat was not found under: $Requested"
    }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:CUBISM_ROOT)) {
        $candidates += $env:CUBISM_ROOT
    }
    $candidates += @(
        "F:\Live2D\Live2D Cubism 5.3.02",
        "C:\Program Files\Live2D Cubism 5.3.02",
        "C:\Program Files (x86)\Live2D Cubism 5.3.02",
        "F:\Live2D\Live2D Cubism 5.2",
        "C:\Program Files\Live2D Cubism 5.2",
        "C:\Program Files (x86)\Live2D Cubism 5.2"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate "CubismEditor5.bat") -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Live2D Cubism 5.2 or 5.3.02 was not found. Set CUBISM_ROOT or pass -CubismRoot."
}

function Invoke-LoggedNative {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$LogPath
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $FilePath @ArgumentList 2>&1 |
            ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) {
                    $_.Exception.Message
                }
                else {
                    $_.ToString()
                }
            } |
            Tee-Object -FilePath $LogPath |
            ForEach-Object { Write-Host $_ }
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Read-PropertiesFile {
    param([string]$Path)

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        $properties[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
    }
    return $properties
}

function Stop-ValidationProcessTree {
    param([System.Diagnostics.Process]$Process)

    if (-not $Process.HasExited) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $Process.Id /T /F | Out-Null
        $Process.WaitForExit(10000) | Out-Null
    }
}

$cubism = Resolve-CubismRoot -Requested $CubismRoot
$officialLauncher = Join-Path $cubism "CubismEditor5.bat"
$java = Join-Path $cubism "app\jre\bin\java.exe"
$hostJar = Join-Path $cubism "app\lib\Live2D_Cubism.jar"
$agent = Join-Path $bundleRoot "turboism-agent.jar"
$plugins = Join-Path $bundleRoot "plugins"
$state = Join-Path $bundleRoot "state"
$logs = Join-Path $bundleRoot "logs"
$resultPath = Join-Path $state "host-validation-result.properties"
$consoleLog = Join-Path $logs "cubism-console.log"
$turboismHome = $bundleRoot
$runId = "parameter-{0}-{1}" -f $ValidationMode, ([DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ"))

foreach ($required in @(
    $officialLauncher,
    $hostJar,
    $agent,
    (Join-Path $plugins "parameter.jar"),
    (Join-Path $plugins "parameter-validation-probe.jar"),
    (Join-Path $plugins "editor-object-peer-validation-probe.jar")
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}
foreach ($directory in @($plugins, $state, $logs)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
Remove-Item -LiteralPath $resultPath -Force -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath $logs -Filter "*validation*.txt" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force
Get-ChildItem -LiteralPath $logs -Filter "*smoke*.txt" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

Write-Host "=== Turboism Automated Parameter Host Validation ===" -ForegroundColor Cyan
Write-Host "runId:   $runId"
Write-Host "mode:    $ValidationMode"
Write-Host "bundle:  $bundleRoot"
Write-Host "cubism:  $cubism"
Write-Host "launcher:$officialLauncher"
Write-Host "result:  $resultPath"

if ($ProbeOnly) {
    Write-Host "[Turboism] Bundle and official Cubism launcher probe passed." -ForegroundColor Green
    exit 0
}

if ($ProbeAgent) {
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw "Cubism Java runtime was not found for the isolated agent-only probe: $java"
    }
    $probeArgs = @(
        "-showversion",
        "-Dturboism.home=$turboismHome",
        "-javaagent:$agent=home=$turboismHome;hostClass=dev.turboism.preview.DoesNotExist;timeoutSeconds=1",
        "-classpath", $agent,
        "dev.turboism.bootstrap.PreviewAgentProbeMain"
    )
    exit (Invoke-LoggedNative `
        -FilePath $java `
        -ArgumentList $probeArgs `
        -LogPath (Join-Path $logs "agent-probe-console.log"))
}

if ([string]::IsNullOrWhiteSpace($ProjectPath)) {
    throw "Automated host validation requires -ProjectPath pointing to an isolated model copy."
}
$resolvedProject = (Resolve-Path -LiteralPath $ProjectPath).Path

# Keep Cubism roaming/local state inside this disposable validation bundle.
$realAppData = $env:APPDATA
$isolatedRoaming = Join-Path $state "AppData\Roaming"
$isolatedLocal = Join-Path $state "AppData\Local"
New-Item -ItemType Directory -Force -Path $isolatedRoaming | Out-Null
New-Item -ItemType Directory -Force -Path $isolatedLocal | Out-Null
$realLive2d = Join-Path $realAppData "Live2D"
$isolatedLive2d = Join-Path $isolatedRoaming "Live2D"
if ((Test-Path -LiteralPath $realLive2d -PathType Container) -and
    -not (Test-Path -LiteralPath $isolatedLive2d -PathType Container)) {
    Copy-Item -LiteralPath $realLive2d -Destination $isolatedLive2d -Recurse -Force
}
$env:APPDATA = $isolatedRoaming
$env:LOCALAPPDATA = $isolatedLocal

$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = @(
    "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED",
    "-Dturboism.home=$turboismHome",
    "-Dturboism.editorObjectValidation.mode=$ValidationMode",
    "-Dturboism.validation.runId=$runId",
    "-Dturboism.validation.exitOnComplete=true",
    "-javaagent:$agent=home=$turboismHome;timeoutSeconds=$TimeoutSeconds"
) -join " "

$command = 'call "{0}" "{1}" > "{2}" 2>&1' -f $officialLauncher, $resolvedProject, $consoleLog
$cmd = Join-Path $env:SystemRoot "System32\cmd.exe"
$process = $null
$forcedCleanup = $false
try {
    # Real-host runs always delegate to the official BAT. Its classpath, native
    # path, working directory, startup flow, and licensing behavior remain intact.
    $process = Start-Process `
        -FilePath $cmd `
        -ArgumentList @("/d", "/s", "/c", $command) `
        -WorkingDirectory $cubism `
        -PassThru

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
            break
        }
        if ($process.HasExited) {
            break
        }
        Start-Sleep -Seconds 2
    }

    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        Stop-ValidationProcessTree -Process $process
        throw "Host validation did not produce a terminal result within $TimeoutSeconds seconds. See $consoleLog"
    }

    $result = Read-PropertiesFile -Path $resultPath
    $status = if ($result.ContainsKey("status")) { $result["status"] } else { "MISSING" }
    Write-Host "[validation] terminal status=$status"

    if (-not $process.WaitForExit(30000)) {
        $forcedCleanup = $true
        Stop-ValidationProcessTree -Process $process
    }
    $launcherExitCode = if ($process.HasExited) { $process.ExitCode } else { -1 }
    Add-Content -LiteralPath $resultPath -Value "launcherExitCode=$launcherExitCode"
    Add-Content -LiteralPath $resultPath -Value "forcedCleanup=$($forcedCleanup.ToString().ToLowerInvariant())"
    Add-Content -LiteralPath $resultPath -Value "hostJarSha256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $hostJar).Hash.ToLowerInvariant())"
    Add-Content -LiteralPath $resultPath -Value "officialLauncherSha256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $officialLauncher).Hash.ToLowerInvariant())"
    Add-Content -LiteralPath $resultPath -Value "agentSha256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $agent).Hash.ToLowerInvariant())"
    Add-Content -LiteralPath $resultPath -Value "fixtureAfterSha256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedProject).Hash.ToLowerInvariant())"

    if ($status -ne "PASS" -or $forcedCleanup -or $launcherExitCode -ne 0) {
        Write-Error "Host validation failed: status=$status launcherExitCode=$launcherExitCode forcedCleanup=$forcedCleanup"
        exit 1
    }

    Write-Host "[Turboism] Automated host validation passed." -ForegroundColor Green
    exit 0
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-ValidationProcessTree -Process $process
    }
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
