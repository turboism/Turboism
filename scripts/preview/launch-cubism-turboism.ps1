[CmdletBinding()]
param(
    [string]$CubismRoot = "",
    [string]$CubismJava = "",
    [string]$GraalJava = "",
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

function Resolve-JavaExecutable {
    param(
        [string]$Requested,
        [string]$Label
    )

    if ([string]::IsNullOrWhiteSpace($Requested)) {
        return ""
    }
    $candidate = $Requested
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        $candidate = Join-Path $candidate "bin\java.exe"
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Label Java executable does not exist: $Requested"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Read-CubismJvmPreference {
    param([string]$Home)

    $config = Join-Path $Home "config.json"
    if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
        return "graalvm"
    }
    if ((Get-Item -LiteralPath $config).Length -gt 65536) {
        throw "Turboism config exceeds 64 KiB: $config"
    }
    try {
        $document = Get-Content -LiteralPath $config -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Turboism config is invalid: $config"
    }
    if ($null -eq $document.launcher -or $null -eq $document.launcher.cubismJvm) {
        return "graalvm"
    }
    if ($document.launcher.cubismJvm -isnot [string] -or
        @("graalvm", "bundled") -notcontains [string]$document.launcher.cubismJvm) {
        throw "Turboism Cubism JVM setting is invalid"
    }
    return [string]$document.launcher.cubismJvm
}

function Test-CompatibleGraalJava {
    param([string]$JavaPath)

    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        return $false
    }
    try {
        $bin = Split-Path -Parent (Resolve-Path -LiteralPath $JavaPath).Path
        $home = Split-Path -Parent $bin
        $release = Join-Path $home "release"
        if (-not (Test-Path -LiteralPath $release -PathType Leaf)
            -or (Get-Item -LiteralPath $release).Length -gt 65536) {
            return $false
        }
        $metadata = Get-Content -LiteralPath $release -Raw -Encoding UTF8 -ErrorAction Stop
        return $metadata -match '(?m)^IMPLEMENTOR="GraalVM[^"]*"\s*$' -and
            $metadata -match '(?m)^GRAALVM_VERSION="25\.2\.[^"]*"\s*$'
    }
    catch { return $false }
}

function Resolve-GraalJava {
    param([string]$Requested)

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $resolved = Resolve-JavaExecutable -Requested $Requested -Label "Graal"
        if (-not (Test-CompatibleGraalJava $resolved)) {
            throw "Graal Java must be a compatible GraalVM 25.2.x executable: $Requested"
        }
        return $resolved
    }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_GRAAL_JAVA)) {
        $candidates += $env:TURBOISM_GRAAL_JAVA
    }
    $candidates += (Join-Path $previewRoot "graalvm\bin\java.exe")
    $candidates += (Join-Path $previewRoot "graal\runtime\bin\java.exe")
    if (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_GRAALVM_HOME)) {
        $candidates += (Join-Path $env:TURBOISM_GRAALVM_HOME "bin\java.exe")
    }
    if (-not [string]::IsNullOrWhiteSpace($env:GRAALVM_HOME)) {
        $candidates += (Join-Path $env:GRAALVM_HOME "bin\java.exe")
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            $candidate = Join-Path $candidate "bin\java.exe"
        }
        if (Test-CompatibleGraalJava $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return ""
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

function Test-GraalLibraryClosure {
    param([string]$PreviewRoot)

    $libraryRoot = Join-Path $PreviewRoot "graal\lib"
    if (-not (Test-Path -LiteralPath $libraryRoot -PathType Container)) {
        throw "Graal is configured but the packaged library directory is missing: $libraryRoot"
    }

    # Match artifacts by role rather than their worktree classifier or a
    # transitive dependency's exact version. The child still receives lib\*.
    $requiredPatterns = @(
        "graal-host-*.jar",
        "jackson-annotations-*.jar",
        "jackson-core-*.jar",
        "jackson-databind-*.jar",
        "collections-*.jar",
        "jniutils-*.jar",
        "js-isolate-windows-amd64-community-*.jar",
        "nativebridge-*.jar",
        "nativeimage-*.jar",
        "polyglot-*.jar",
        "truffle-api-*.jar",
        "word-*.jar"
    )
    $missing = $requiredPatterns | Where-Object {
        -not (Get-ChildItem -LiteralPath $libraryRoot -Filter $_ -File | Select-Object -First 1)
    }
    if ($missing.Count -gt 0) {
        throw "Graal is configured but its packaged library closure is incomplete: $($missing -join ', ')"
    }
}

$cubism = Resolve-CubismRoot -Requested $CubismRoot
$defaultCubismJava = Join-Path $cubism "app\jre\bin\java.exe"
$cubismJvm = Read-CubismJvmPreference -Home $previewRoot
$requestedCubismJava = if (-not [string]::IsNullOrWhiteSpace($CubismJava)) {
    $CubismJava
}
elseif ($cubismJvm -eq "graalvm" -and -not [string]::IsNullOrWhiteSpace($env:TURBOISM_CUBISM_JAVA)) {
    $env:TURBOISM_CUBISM_JAVA
}
else {
    ""
}
$java = if (-not [string]::IsNullOrWhiteSpace($CubismJava)) {
    Resolve-JavaExecutable -Requested $CubismJava -Label "Cubism"
}
elseif ($cubismJvm -eq "bundled") {
    (Resolve-Path -LiteralPath $defaultCubismJava).Path
}
elseif (-not [string]::IsNullOrWhiteSpace($requestedCubismJava)) {
    Resolve-JavaExecutable -Requested $requestedCubismJava -Label "Cubism"
}
else {
    $resolved = Resolve-GraalJava -Requested ""
    if ([string]::IsNullOrWhiteSpace($resolved)) {
        Write-Warning "GraalVM is unavailable; this launch will use Cubism bundled Java. Install GraalVM from https://www.graalvm.org/downloads/ and select it again in Turboism Settings."
        $cubismJvm = "bundled"
        (Resolve-Path -LiteralPath $defaultCubismJava).Path
    }
    else { $resolved }
}
$graalHostJava = Resolve-GraalJava -Requested $GraalJava
$graalClassPath = Join-Path $previewRoot "graal\lib\*"
$hostJar = Join-Path $cubism "app\lib\Live2D_Cubism.jar"
$agent = Join-Path $previewRoot "turboism-agent.jar"
$plugin = Join-Path $previewRoot "plugins\project-inspector.jar"

$requiredFiles = @($java, $hostJar, $agent, $plugin)
foreach ($required in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

Write-Host "[Turboism] Preview:     $previewRoot"
Write-Host "[Turboism] Cubism:      $cubism"
Write-Host "[Turboism] Cubism Java: $java"
if ([string]::IsNullOrWhiteSpace($graalHostJava)) {
    Write-Host "[Turboism] Graal Java:  not configured; script runtime disabled"
}
else {
    Write-Host "[Turboism] Graal Java:  $graalHostJava"
}

if ($ProbeOnly) {
    if (-not [string]::IsNullOrWhiteSpace($graalHostJava)) {
        Test-GraalLibraryClosure -PreviewRoot $previewRoot
        Write-Host "[Turboism] Graal library closure: valid."
    }
    else {
        Write-Host "[Turboism] Graal library closure: skipped (Graal not configured)."
    }
    Write-Host "[Turboism] Probe passed: launcher prerequisites only; Cubism host readiness was not checked."
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
    "-Djava.locale.providers=COMPAT,SPI"
)
if (-not [string]::IsNullOrWhiteSpace($graalHostJava)) {
    $javaArgs += "-Dturboism.graal.enabled=true"
    $javaArgs += "-Dturboism.graal.java=$graalHostJava"
    $javaArgs += "-Dturboism.graal.classpath=$graalClassPath"
}
else {
    $javaArgs += "-Dturboism.graal.enabled=false"
}
$javaArgs += "com.live2d.cubism.CECubismEditorApp"

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
