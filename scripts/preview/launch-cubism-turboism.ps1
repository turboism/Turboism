[CmdletBinding()]
param(
    [string]$CubismRoot = "",
    [string]$ProjectPath = "",
    [switch]$ProbeOnly,
    [switch]$ProbeAgent
)

$ErrorActionPreference = "Stop"
$previewRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-CubismRoot {
    param([string]$Requested)

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $candidate = $Requested
        if ([System.IO.Path]::GetExtension($candidate) -ieq ".bat") {
            $candidate = Split-Path -Parent $candidate
        }
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
        throw "Cubism root does not exist: $Requested"
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
        $line = Get-Content -LiteralPath $launcher | Where-Object { $_ -match '^set CLASS_PATH=(.+)$' } | Select-Object -First 1
        if ($line -and $line -match '^set CLASS_PATH=(.+)$') {
            return $Matches[1]
        }
    }
    return "app\lib\*;app\lib\jogl\*"
}

$cubism = Resolve-CubismRoot -Requested $CubismRoot
$java = Join-Path $cubism "app\jre\bin\java.exe"
$hostJar = Join-Path $cubism "app\lib\Live2D_Cubism.jar"
$agent = Join-Path $previewRoot "turboism-agent.jar"
$plugin = Join-Path $previewRoot "plugins\project-inspector.jar"

$requiredFiles = @($java, $hostJar, $agent, $plugin)
foreach ($required in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

Write-Host "[Turboism] Preview: $previewRoot"
Write-Host "[Turboism] Cubism:  $cubism"
Write-Host "[Turboism] Java:    $java"

if ($ProbeOnly) {
    Write-Host "[Turboism] Probe passed."
    exit 0
}

if ($ProbeAgent) {
    $probeArgs = @(
        "-Dturboism.home=$previewRoot",
        "-javaagent:$agent=hostClass=dev.turboism.preview.DoesNotExist;timeoutSeconds=1",
        "-classpath", $agent,
        "dev.turboism.bootstrap.PreviewAgentProbeMain"
    )
    & $java @probeArgs
    exit $LASTEXITCODE
}

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
    "-Dturboism.home=$previewRoot",
    "-javaagent:$agent=home=$previewRoot;timeoutSeconds=120",
    "-Djava.locale.providers=COMPAT,SPI",
    "com.live2d.cubism.CECubismEditorApp"
)

if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
    $javaArgs += (Resolve-Path -LiteralPath $ProjectPath).Path
}

$env:PATH = (Join-Path $cubism "app\dll64") + ";" + $env:PATH
Push-Location $cubism
try {
    & $java @javaArgs
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $exitCode
