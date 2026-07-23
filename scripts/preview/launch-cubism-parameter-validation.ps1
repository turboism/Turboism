[CmdletBinding()]
param(
    [string]$CubismRoot = "",
    [string]$ProjectPath = "",
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
        if (Test-Path -LiteralPath (Join-Path $candidate "app\jre\bin\java.exe") -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        throw "Cubism Java runtime was not found under: $Requested"
    }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:CUBISM_ROOT)) {
        $candidates += $env:CUBISM_ROOT
    }
    $candidates += @(
        "F:\Live2D\Live2D Cubism 5.3.02",
        "C:\Program Files\Live2D Cubism 5.3.02",
        "C:\Program Files (x86)\Live2D Cubism 5.3.02"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate "app\jre\bin\java.exe") -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Live2D Cubism 5.3.02 was not found. Set CUBISM_ROOT or pass -CubismRoot."
}

function Read-OfficialClassPath {
    param([string]$Root)

    $launcher = Join-Path $Root "CubismEditor5.bat"
    if (Test-Path -LiteralPath $launcher -PathType Leaf) {
        $line = Get-Content -LiteralPath $launcher |
            Where-Object { $_ -match '^set CLASS_PATH=(.+)$' } |
            Select-Object -First 1
        if ($line -and $line -match '^set CLASS_PATH=(.+)$') {
            return $Matches[1]
        }
    }
    return "app\lib\*;app\lib\jogl\*"
}

function Invoke-LoggedNative {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$LogPath
    )

    # Windows PowerShell 5.1 turns any native stderr line into an ErrorRecord.
    # Java writes normal -showversion and some host diagnostics to stderr, so
    # temporarily allow those records, convert them back to text, and use only
    # the native process exit code to decide success or failure.
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
        $nativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return $nativeExitCode
}

$cubism = Resolve-CubismRoot -Requested $CubismRoot
$java = Join-Path $cubism "app\jre\bin\java.exe"
$hostJar = Join-Path $cubism "app\lib\Live2D_Cubism.jar"
$agent = Join-Path $bundleRoot "turboism-agent.jar"
$plugins = Join-Path $bundleRoot "plugins"
$state = Join-Path $bundleRoot "state"
$logs = Join-Path $bundleRoot "logs"
$turboismHome = $bundleRoot

foreach ($required in @($java, $hostJar, $agent, (Join-Path $plugins "parameter.jar"), (Join-Path $plugins "parameter-validation-probe.jar"))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}
foreach ($directory in @($plugins, $state, $logs)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

Write-Host "=== Turboism Parameter Validation ===" -ForegroundColor Cyan
Write-Host "bundle:  $bundleRoot"
Write-Host "cubism:  $cubism"
Write-Host "agent:   $agent"
Write-Host "plugins: $plugins"
Write-Host "logs:    $logs"

if ($ProbeOnly) {
    Write-Host "[Turboism] Bundle and Cubism probe passed." -ForegroundColor Green
    exit 0
}

if ($ProbeAgent) {
    $probeArgs = @(
        "-showversion",
        "-Dturboism.home=$turboismHome",
        "-javaagent:$agent=home=$turboismHome;hostClass=dev.turboism.preview.DoesNotExist;timeoutSeconds=1",
        "-classpath", $agent,
        "dev.turboism.bootstrap.PreviewAgentProbeMain"
    )
    $probeExitCode = Invoke-LoggedNative `
        -FilePath $java `
        -ArgumentList $probeArgs `
        -LogPath (Join-Path $logs "agent-probe-console.log")
    exit $probeExitCode
}

# Keep Cubism's roaming/local state inside this disposable validation bundle.
# Seed Live2D settings once so the isolated launch can reuse the current license
# and normal Editor preferences without writing back to the real profile.
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

$classPath = Read-OfficialClassPath -Root $cubism
$nativePath = "app\dll64;app\dll64\windows-amd64"
$javaArgs = @(
    "-classpath", $classPath,
    "-Djava.library.path=$nativePath",
    "-Djogamp.gluegen.UseTempJarCache=false",
    "-Dsun.java2d.d3d=false",
    "-Duser.language=zh",
    "-XX:MaxRAMPercentage=100",
    "-showversion",
    "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED",
    "-Dturboism.home=$turboismHome",
    "-javaagent:$agent=home=$turboismHome;timeoutSeconds=120",
    "-Djava.locale.providers=COMPAT,SPI",
    "com.live2d.cubism.CECubismEditorApp"
)
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
    $javaArgs += (Resolve-Path -LiteralPath $ProjectPath).Path
}

$env:PATH = (Join-Path $cubism "app\dll64") + ";" + $env:PATH
Push-Location $cubism
try {
    $exitCode = Invoke-LoggedNative `
        -FilePath $java `
        -ArgumentList $javaArgs `
        -LogPath (Join-Path $logs "cubism-console.log")
}
finally {
    Pop-Location
}
exit $exitCode
