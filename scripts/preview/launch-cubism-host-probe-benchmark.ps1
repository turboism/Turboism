[CmdletBinding()]
param(
    [ValidateSet("reorder", "wheel", "pan", "artmesh", "record")]
    [string]$Workload = "reorder",
    [string]$CubismRoot = "C:\Program Files\Live2D Cubism 5.3.04",
    [string]$ProjectPath = "F:\Live2D\Projects\矢车菊机械未来1.cmo3",
    [string]$BundleRoot = "",
    [string]$WorktreeId = "",
    [ValidateSet("native", "installed")]
    [string]$Variant = "native",
    [int]$Iterations = 24,
    [int]$RecordSeconds = 30,
    [ValidateSet("wheel", "pan", "artmesh")]
    [string]$RecordKind = "wheel",
    [string]$RecordOutput = "",
    [string]$Legs = "apply,undoRedo,attribution",
    [switch]$Calibration,
    [string[]]$JvmOption = @(),
    [string[]]$HomeFile = @(),
    [string[]]$ReadyMarker = @(),
    [string[]]$FailureMarker = @(),
    [int]$TimeoutSeconds = 3600,
    [string]$EvidenceRoot = "",
    [switch]$DryRun
)

# Native-Windows launcher for the model-update-skip host-probe exerciser.
#
# Windows PowerShell 5.1 compatible. This file is UTF-8 with a BOM so the default
# Chinese fixture path survives PowerShell's ANSI default for script files.
#
# Workloads:
#   reorder  - real OS Robot drag in the native Parts palette (default); the
#              current isolated layout keeps that palette undocked and the run
#              fails with an explicit "not showing" diagnostic.
#   wheel    - the AWT-wheel workload, JVM options mirrored from
#              scripts/preview/run-model-update-skip-host-validation.sh on-wheel.
#              Add -Dturboism.validation.humanTrace=<tsv> to replay a recorded
#              human wheel/pan/ArtMesh gesture through the system EventQueue.
#   pan      - the continuous pan drag, mirrored from on-pan.
#   artmesh  - the single-ArtMesh drag, mirrored from on-artmesh.
#   record   - records a real human trace (-RecordKind wheel|pan|artmesh,
#              -RecordSeconds, -RecordOutput); no benchmark result is produced.
#
# Isolation contract (same shape as launch-cubism-parameter-validation.ps1):
#   * disposable Turboism home under %TEMP%, never the user's real home;
#   * the fixture is copied into the run directory and the copy is what Cubism
#     opens, so the original project is never written;
#   * the official CubismEditor5.bat is the only Cubism entry point;
#   * only the process this script started is stopped, and only after the
#     result file or the timeout.
#
# The exerciser plugin is staged into the isolated home only. Never copy it into
# a real Turboism home.

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptRoot "..\..")).Path

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
        "C:\Program Files\Live2D Cubism 5.3.04",
        "C:\Program Files (x86)\Live2D Cubism 5.3.04"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate "CubismEditor5.bat") -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "Live2D Cubism 5.3.04 was not found. Set CUBISM_ROOT or pass -CubismRoot."
}

# A managed Cubism BAT is not an official launcher: it forwards to the user's real
# Turboism home (C:\Users\<user>\AppData\Local\Turboism) and that launcher clears
# JAVA_TOOL_OPTIONS, so launching it would break isolation and silently drop our
# -D options. The installer keeps the pristine BAT next to it as
# *.turboism-original.bak; that backup is the launcher this script must use.
function Test-ManagedBat {
    param([string]$Path)

    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    return ($text.Contains("TURBOISM MANAGED BEGIN") -or $text.Contains("TURBOISM MANAGED END"))
}

function Resolve-OfficialLauncher {
    param([string]$Root)

    $managed = Join-Path $Root "CubismEditor5.bat"
    if (-not (Test-Path -LiteralPath $managed -PathType Leaf)) {
        throw "CubismEditor5.bat was not found under: $Root"
    }
    if (-not (Test-ManagedBat -Path $managed)) {
        return [pscustomobject]@{ Source = $managed; Reason = "unmanaged official BAT" }
    }
    $backup = $managed + ".turboism-original.bak"
    if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) {
        throw ("CubismEditor5.bat is the Turboism managed wrapper and the pristine backup is missing: " +
            $backup + ". Repair the Turboism install before running this benchmark.")
    }
    if (Test-ManagedBat -Path $backup) {
        throw "The Turboism backup BAT is itself managed; refusing to launch: $backup"
    }
    return [pscustomobject]@{ Source = $backup; Reason = "managed wrapper detected; using the pristine backup" }
}

# Stages a copy of the pristine BAT inside the run directory with every %~dp0
# replaced by the Cubism root. The original uses "cd /d %~dp0" and puts %~dp0 on
# PATH, so a copy living elsewhere would resolve app\jre and app\dll64 against the
# run directory. Everything except that literal is copied byte for byte, and
# nothing under Program Files is ever written.
function New-StagedLauncher {
    param([string]$Source, [string]$Root, [string]$Destination)

    $rootPrefix = $Root.TrimEnd('\', '/') + '\'
    $bytes = [System.IO.File]::ReadAllBytes($Source)
    $needle = [System.Text.Encoding]::ASCII.GetBytes('%~dp0')
    $replacement = [System.Text.Encoding]::ASCII.GetBytes($rootPrefix)
    $output = New-Object System.Collections.Generic.List[byte]
    $index = 0
    $replacements = 0
    while ($index -lt $bytes.Length) {
        $matched = ($index + $needle.Length) -le $bytes.Length
        if ($matched) {
            for ($offset = 0; $offset -lt $needle.Length; $offset++) {
                if ($bytes[$index + $offset] -ne $needle[$offset]) { $matched = $false; break }
            }
        }
        if ($matched) {
            foreach ($value in $replacement) { $output.Add($value) }
            $index += $needle.Length
            $replacements++
        }
        else {
            $output.Add($bytes[$index])
            $index++
        }
    }
    if ($replacements -eq 0) {
        throw "The pristine BAT has no %~dp0 to rewrite; refusing to stage it: $Source"
    }
    [System.IO.File]::WriteAllBytes($Destination, $output.ToArray())
    $stagedText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($Destination))
    if ($stagedText.Contains("TURBOISM MANAGED")) {
        throw "The staged BAT still contains TURBOISM MANAGED text; refusing to launch: $Destination"
    }
    if ($stagedText.Contains("launch-cubism-turboism.ps1")) {
        throw "The staged BAT still forwards to the managed launcher; refusing to launch: $Destination"
    }
    return $replacements
}

function Resolve-BundleRoot {
    param([string]$Requested, [string]$RequestedWorktreeId)

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $resolved = (Resolve-Path -LiteralPath $Requested).Path
        if (-not (Test-Path -LiteralPath (Join-Path $resolved "turboism-agent.jar") -PathType Leaf)) {
            throw "Preview bundle has no turboism-agent.jar: $resolved"
        }
        return $resolved
    }
    $previewRoot = Join-Path $repoRoot "build\preview"
    if (-not [string]::IsNullOrWhiteSpace($RequestedWorktreeId)) {
        $candidate = Join-Path $previewRoot $RequestedWorktreeId
        if (-not (Test-Path -LiteralPath (Join-Path $candidate "turboism-agent.jar") -PathType Leaf)) {
            throw "Preview bundle not found for worktree '$RequestedWorktreeId': $candidate"
        }
        return (Resolve-Path -LiteralPath $candidate).Path
    }
    if (-not (Test-Path -LiteralPath $previewRoot -PathType Container)) {
        throw "Preview bundle directory is missing: $previewRoot. Run: ./gradlew --no-watch-fs previewBundle"
    }
    $found = @(Get-ChildItem -LiteralPath $previewRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "turboism-agent.jar") -PathType Leaf })
    if ($found.Count -eq 1) { return $found[0].FullName }
    if ($found.Count -eq 0) {
        throw "No preview bundle with turboism-agent.jar under $previewRoot. Run: ./gradlew --no-watch-fs previewBundle"
    }
    throw ("Several preview bundles exist; pass -WorktreeId or -BundleRoot. Candidates: " +
        (($found | ForEach-Object { $_.Name }) -join ", "))
}

function Stop-BenchmarkProcessTree {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process) { return }
    if (-not $Process.HasExited) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $Process.Id /T /F | Out-Null
        $Process.WaitForExit(15000) | Out-Null
    }
}

function Read-PropertiesFile {
    param([string]$Path)

    $properties = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
        $separator = $trimmed.IndexOf("=")
        $properties[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
    }
    return $properties
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

# Searches every log the run wrote for a marker. Returns the first match's path,
# or "" when the marker is absent. Markers come from the shared runner's
# --ready-marker/--failure-marker contract.
function Find-LogMarker {
    param([string]$Root, [string]$Marker)

    if ([string]::IsNullOrWhiteSpace($Marker) -or -not (Test-Path -LiteralPath $Root -PathType Container)) {
        return ""
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $Root -Recurse -File -ErrorAction SilentlyContinue)) {
        try {
            $text = [System.IO.File]::ReadAllText($file.FullName)
        }
        catch [System.IO.IOException] {
            continue
        }
        if ($text.Contains($Marker)) { return $file.FullName }
    }
    return ""
}

$cubism = Resolve-CubismRoot -Requested $CubismRoot
$launcherChoice = Resolve-OfficialLauncher -Root $cubism
$officialLauncher = $launcherChoice.Source
$hostJar = Join-Path $cubism "app\lib\Live2D_Cubism.jar"
$realTurboismHome = Join-Path $env:LOCALAPPDATA "Turboism"
$bundle = Resolve-BundleRoot -Requested $BundleRoot -RequestedWorktreeId $WorktreeId
$agentSource = Join-Path $bundle "turboism-agent.jar"
$exerciserSource = Join-Path $repoRoot "build\model-update-skip-host-validation-exerciser.jar"
$homeConfigSource = Join-Path $repoRoot "testing\host-validation\texture-upload\config.json"

if ([string]::IsNullOrWhiteSpace($ProjectPath)) {
    throw "The host-probe benchmark requires -ProjectPath pointing at the source .cmo3 project."
}
if (-not (Test-Path -LiteralPath $ProjectPath -PathType Leaf)) {
    throw "Source project was not found: $ProjectPath"
}
$sourceProject = (Resolve-Path -LiteralPath $ProjectPath).Path
foreach ($required in @($officialLauncher, $hostJar, $agentSource, $exerciserSource, $homeConfigSource)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

# Mirrored from scripts/preview/run-model-update-skip-host-validation.sh for the
# on-wheel / on-pan / on-artmesh modes. Keep these identical to that wrapper;
# any intentional divergence belongs in -JvmOption, not here.
$mirroredBaseline = @(
    "-Dturboism.optimization.incrementalUpdate=false",
    "-Dturboism.optimization.warpPositionProjection=false",
    "-Dturboism.optimization.imageArchiveReuse=false",
    "-Dturboism.optimization.floatArrayParseCache=false",
    "-Dturboism.optimization.textureUploadPreparation=false"
)
# Reorder measures the host's own reorder cost, so its default baseline pins
# every optimization off, including the ones the managed launcher turns on.
$reorderNativeBaseline = @(
    "-Dturboism.optimization.modelUpdateSkip=false",
    "-Dturboism.optimization.uniformLocationCache=false",
    "-Dturboism.optimization.uploadElision=false",
    "-Dturboism.optimization.inputPathElision=false",
    "-Dturboism.optimization.mesaGlThread=false",
    "-Dturboism.optimization.reinitPairElision=false"
)

$workloadOptions = New-Object System.Collections.Generic.List[string]
$workloadOptions.Add("-Dturboism.validation.fixtureName=$(Split-Path -Leaf $sourceProject)")
$recordOutputFull = ""
switch ($Workload) {
    "wheel" {
        $workloadOptions.Add("-Dturboism.optimization.modelUpdateSkip=true")
        $workloadOptions.Add("-Dturboism.validation.modelUpdateWorkload=wheel")
    }
    "record" {
        if ([string]::IsNullOrWhiteSpace($RecordOutput)) {
            throw "-RecordOutput is required for the record workload"
        }
        if ($RecordSeconds -lt 3 -or $RecordSeconds -gt 600) {
            throw "-RecordSeconds must be 3..600"
        }
        $recordOutputFull = [System.IO.Path]::GetFullPath($RecordOutput)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $recordOutputFull) | Out-Null
        $workloadOptions.Add("-Dturboism.optimization.modelUpdateSkip=true")
        $workloadOptions.Add("-Dturboism.validation.modelUpdateWorkload=wheel")
        $workloadOptions.Add("-Dturboism.validation.recordHumanTrace=$recordOutputFull")
        $workloadOptions.Add("-Dturboism.validation.recordSeconds=$RecordSeconds")
        $workloadOptions.Add("-Dturboism.validation.recordKind=$RecordKind")
    }
    "pan" {
        $workloadOptions.Add("-Dturboism.optimization.modelUpdateSkip=true")
        $workloadOptions.Add("-Dturboism.validation.modelUpdateWorkload=wheel")
        $workloadOptions.Add("-Dturboism.validation.nativeInteraction=pan")
    }
    "artmesh" {
        $workloadOptions.Add("-Dturboism.optimization.modelUpdateSkip=true")
        $workloadOptions.Add("-Dturboism.validation.modelUpdateWorkload=wheel")
        $workloadOptions.Add("-Dturboism.validation.nativeInteraction=artmesh")
    }
    default {
        $workloadOptions.Add("-Dturboism.optimization.modelUpdateSkip=true")
        $workloadOptions.Add("-Dturboism.validation.modelUpdateWorkload=wheel")
        $workloadOptions.Add("-Dturboism.validation.nativeInteraction=reorder")
    }
}
foreach ($option in $mirroredBaseline) { $workloadOptions.Add($option) }
if ($Calibration) {
    $workloadOptions.Add("-Dturboism.validation.modelUpdateCalibration=true")
}
# powershell -File passes "a,b" through as ONE argument, so an entry is split on
# commas here. That keeps both invocation styles working:
#   -JvmOption -Dx=1,-Dy=2            (one argument, comma separated)
#   -JvmOption -Dx=1 -JvmOption -Dy=2 (repeated parameter)
$jvmOptionEntries = @($JvmOption | ForEach-Object { $_ -split "," } |
    ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
foreach ($option in $jvmOptionEntries) {
    if ($option -notmatch '^(-D|-javaagent:)') { throw "-JvmOption entries must start with -D or -javaagent:: $option" }
    $workloadOptions.Add($option)
}

$legNames = @($Legs.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
$allowedLegs = @("apply", "undoRedo", "attribution")
foreach ($leg in $legNames) {
    if ($allowedLegs -notcontains $leg) {
        throw "Unknown leg '$leg'. Allowed: $($allowedLegs -join ', ')"
    }
}
if ($legNames.Count -eq 0) { $legNames = $allowedLegs }

$effectiveIterations = $Iterations
if ($Calibration -and $Workload -eq "reorder") { $effectiveIterations = 4 }
if ($effectiveIterations -lt 1 -or $effectiveIterations -gt 500) {
    throw "-Iterations must be 1..500"
}

# Home files: "localPath:relativePathInHome". The separator is the last colon so a
# drive-lettered local path keeps its own colon.
$homeFilePlan = @()
$homeFileEntries = @($HomeFile | ForEach-Object { $_ -split "," } |
    ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
foreach ($entry in $homeFileEntries) {
    $separator = $entry.LastIndexOf(":")
    if ($separator -lt 2 -or $separator -eq $entry.Length - 1) {
        throw "-HomeFile must be 'localPath:relativePathInHome', got: $entry"
    }
    $local = $entry.Substring(0, $separator)
    $relative = $entry.Substring($separator + 1)
    if (-not (Test-Path -LiteralPath $local -PathType Leaf)) {
        throw "-HomeFile local path was not found: $local"
    }
    if ($relative -match '(^[\\/])' -or $relative.Contains("..")) {
        throw "-HomeFile destination must be a relative path inside the home: $relative"
    }
    $homeFilePlan += [pscustomobject]@{ Source = (Resolve-Path -LiteralPath $local).Path; Relative = $relative }
}

$stamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$runId = "{0}-{1}-{2}" -f $Workload, $Variant, $stamp
$runRoot = Join-Path $env:TEMP ("turboism-host-probe-" + $stamp)
$homeRoot = Join-Path $runRoot "home"
$pluginsRoot = Join-Path $homeRoot "plugins"
$pluginStateRoot = Join-Path $homeRoot "state\dev.turboism.validation.model-update-skip"
$logsRoot = Join-Path $homeRoot "logs"
$projectRoot = Join-Path $runRoot "project"
$projectCopy = Join-Path $projectRoot (Split-Path -Leaf $sourceProject)
$requestPath = Join-Path $pluginStateRoot "reorder-request.properties"
$resultPath = Join-Path $pluginStateRoot "result.txt"
$consoleLog = Join-Path $logsRoot "cubism-console.log"
$reportName = switch ($Workload) {
    "reorder" { "reorder-benchmark.txt" }
    "wheel" { "wheel-benchmark.txt" }
    "record" { "wheel-benchmark.txt" }
    default { "interaction-benchmark.txt" }
}
$reportPath = Join-Path $pluginStateRoot $reportName
$evidencePath = if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
    Join-Path $env:TEMP ("turboism-host-probe-evidence\" + $stamp)
}
else { [System.IO.Path]::GetFullPath($EvidenceRoot) }

$requestText = ""
if ($Workload -eq "reorder") {
    $requestText = (@(
        "modelUpdateWorkload=reorder",
        ("reorderLegs=" + ($legNames -join ",")),
        ("reorderIterations=" + $effectiveIterations),
        ("fixtureName=" + (Split-Path -Leaf $projectCopy)),
        ("calibration=" + $Calibration.ToString().ToLowerInvariant())
    ) -join "`n") + "`n"
}

$javaToolOptions = New-Object System.Collections.Generic.List[string]
$javaToolOptions.Add("-Djava.locale.providers=CLDR,SPI")
$javaToolOptions.Add("-Dturboism.home=$homeRoot")
foreach ($option in $workloadOptions) { $javaToolOptions.Add($option) }
$agentDetectionTimeout = [Math]::Min([Math]::Max($TimeoutSeconds, 30), 180)
$javaToolOptions.Add("-javaagent:$agentSource=home=$homeRoot;timeoutSeconds=$agentDetectionTimeout")

Write-Host "=== Turboism host-probe benchmark (native Windows) ===" -ForegroundColor Cyan
Write-Host "runId:        $runId"
Write-Host "workload:     $Workload"
Write-Host "variant:      $Variant$(if ($Workload -ne 'reorder') { ' (applies to reorder only)' })"
Write-Host "cubism:       $cubism"
Write-Host "launcher:     $officialLauncher"
Write-Host "launcher kind:$($launcherChoice.Reason)"
Write-Host "host jar:     $hostJar"
Write-Host "bundle:       $bundle"
Write-Host "agent:        $agentSource"
Write-Host "exerciser:    $exerciserSource"
Write-Host "source:       $sourceProject"
Write-Host "source bytes: $((Get-Item -LiteralPath $sourceProject).Length)"
Write-Host "project copy: $projectCopy"
Write-Host "home:         $homeRoot"
Write-Host "evidence:     $evidencePath"
Write-Host "report:       $reportName"
Write-Host "result:       result.txt"
Write-Host "timeout:      $TimeoutSeconds s"
Write-Host "calibration:  $Calibration"
if ($Workload -eq "reorder") {
    Write-Host "iterations:   $effectiveIterations"
    Write-Host "legs:         $($legNames -join ', ')"
}
if ($ReadyMarker.Count -gt 0) { Write-Host "ready markers:   $($ReadyMarker -join ' | ')" }
if ($FailureMarker.Count -gt 0) { Write-Host "failure markers: $($FailureMarker -join ' | ')" }
if ($jvmOptionEntries.Count -gt 0) { Write-Host "extra -D:        $($jvmOptionEntries -join ' ')" }
if ($homeFilePlan.Count -gt 0) {
    Write-Host "home files:"
    foreach ($entry in $homeFilePlan) { Write-Host ("  {0} -> {1}" -f $entry.Source, $entry.Relative) }
}
if ($requestText.Length -gt 0) {
    Write-Host "--- reorder-request.properties ---"
    Write-Host $requestText.TrimEnd()
}
Write-Host "--- JAVA_TOOL_OPTIONS ---"
Write-Host ($javaToolOptions -join " ")

if ($DryRun) {
    Write-Host ""
    Write-Host "[dry-run] no directory was created, nothing was copied, no hash was computed and Cubism was not launched."
    Write-Host "[dry-run] planned evidence: $reportName, result.txt, plugin state files, logs\*, run-envelope.properties"
    Write-Host "[dry-run] the real run verifies the fixture copy SHA-256 equals the source and the source is unchanged afterwards."
    Write-Host "[dry-run] expected terminal markers: result.txt status=PASS and $reportName status=PASS"
    Write-Host "[dry-run] remove -DryRun to execute."
    exit 0
}

foreach ($directory in @($runRoot, $homeRoot, $pluginsRoot, $pluginStateRoot, $logsRoot, $projectRoot, $evidencePath)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

# Isolated Turboism home from the harness config; pluginDirs stays ["plugins"].
Copy-Item -LiteralPath $homeConfigSource -Destination (Join-Path $homeRoot "config.json") -Force
Copy-Item -LiteralPath $agentSource -Destination (Join-Path $homeRoot "turboism-agent.jar") -Force
Copy-Item -LiteralPath $exerciserSource -Destination (Join-Path $pluginsRoot "model-update-skip-host-validation-exerciser.jar") -Force
foreach ($entry in $homeFilePlan) {
    $destination = Join-Path $homeRoot $entry.Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $entry.Source -Destination $destination -Force
}
if ($requestText.Length -gt 0) {
    Write-Utf8NoBom -Path $requestPath -Text $requestText
}

$runPropertiesText = (@(
    "runId=$runId",
    "workload=$Workload",
    "variant=$Variant",
    "cubism=$cubism",
    "hostJar=$hostJar",
    "bundle=$bundle",
    "agent=$agentSource",
    "exerciser=$exerciserSource",
    "sourceProject=$sourceProject",
    "projectCopy=$projectCopy",
    ("iterations=" + $effectiveIterations),
    ("calibration=" + $Calibration.ToString().ToLowerInvariant()),
    ("legs=" + ($legNames -join ",")),
    ("homeFiles=" + (($homeFilePlan | ForEach-Object { $_.Relative }) -join ",")),
    "javaToolOptions=$($javaToolOptions -join ' ')"
) -join "`n") + "`n"
Write-Utf8NoBom -Path (Join-Path $runRoot "run.properties") -Text $runPropertiesText

# Stage the launcher inside the run directory. The pristine BAT resolves its
# app\ paths against its own directory, so it cannot be run from Program Files.
$launchBat = Join-Path $runRoot "launch-cubism-official.bat"
$dp0Replacements = New-StagedLauncher -Source $officialLauncher -Root $cubism -Destination $launchBat
Write-Host "[stage] staged launcher: $launchBat (%~dp0 replacements=$dp0Replacements)"

# The copy is what Cubism opens; the source is only read.
$sourceHash = ""
$copyHash = ""
Write-Host "[stage] copying the project into the run directory (this is the slow step)..."
Copy-Item -LiteralPath $sourceProject -Destination $projectCopy -Force
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceProject).Hash.ToLowerInvariant()
$copyHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $projectCopy).Hash.ToLowerInvariant()
if ($sourceHash -ne $copyHash) {
    throw "Fixture copy digest mismatch: source=$sourceHash copy=$copyHash"
}
Write-Host "[stage] copy verified: sha256=$copyHash"
Add-Content -LiteralPath (Join-Path $runRoot "run.properties") -Value "sourceSha256Before=$sourceHash"

# Keep Cubism's roaming/local state inside this disposable run, as the existing
# native parameter launcher does.
$realAppData = $env:APPDATA
$realLocalAppData = $env:LOCALAPPDATA
$isolatedRoaming = Join-Path $runRoot "state\AppData\Roaming"
$isolatedLocal = Join-Path $runRoot "state\AppData\Local"
New-Item -ItemType Directory -Force -Path $isolatedRoaming | Out-Null
New-Item -ItemType Directory -Force -Path $isolatedLocal | Out-Null
$realLive2d = Join-Path $realAppData "Live2D"
$isolatedLive2d = Join-Path $isolatedRoaming "Live2D"
if ((Test-Path -LiteralPath $realLive2d -PathType Container) -and
    -not (Test-Path -LiteralPath $isolatedLive2d -PathType Container)) {
    robocopy $realLive2d $isolatedLive2d /E /XD backup cache /XF *.cmo3 *.bak /R:1 /W:1 /NP /NFL /NDL | Out-Null
}

$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$previousAppData = $env:APPDATA
$previousLocalAppData = $env:LOCALAPPDATA
$env:JAVA_TOOL_OPTIONS = $javaToolOptions -join " "
$env:APPDATA = $isolatedRoaming
$env:LOCALAPPDATA = $isolatedLocal

$cmd = Join-Path $env:SystemRoot "System32\cmd.exe"
$command = 'call "{0}" "{1}" > "{2}" 2>&1' -f $launchBat, $projectCopy, $consoleLog
foreach ($forbidden in @($realTurboismHome, "launch-cubism-turboism.ps1")) {
    if ($command.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw "The launch command references '$forbidden'; refusing to launch: $command"
    }
}
$process = $null
$forcedCleanup = $false
$status = "MISSING"
$markerFailure = ""
try {
    Write-Host "[launch] $launchBat"
    $process = Start-Process -FilePath $cmd -ArgumentList @("/d", "/s", "/c", $command) `
        -WorkingDirectory $cubism -PassThru
    Write-Host "[launch] launcher pid=$($process.Id)"

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastProgress = ""
    $readySeen = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($marker in $FailureMarker) {
            $hit = Find-LogMarker -Root $logsRoot -Marker $marker
            if ($hit.Length -gt 0) {
                $markerFailure = "failure marker '$marker' in $hit"
                break
            }
        }
        if ($markerFailure.Length -gt 0) { break }
        if (-not $readySeen -and $ReadyMarker.Count -gt 0) {
            foreach ($marker in $ReadyMarker) {
                $hit = Find-LogMarker -Root $logsRoot -Marker $marker
                if ($hit.Length -gt 0) {
                    Write-Host "[ready] marker '$marker' in $hit"
                    $readySeen = $true
                    break
                }
            }
        }
        if (Test-Path -LiteralPath $resultPath -PathType Leaf) { break }
        if ($process.HasExited) { break }
        $progressFile = if ($Workload -eq "reorder") {
            Join-Path $pluginStateRoot "reorder-progress.txt"
        }
        else { Join-Path $pluginStateRoot "wheel-progress.txt" }
        if (Test-Path -LiteralPath $progressFile -PathType Leaf) {
            try {
                $progress = ([System.IO.File]::ReadAllLines($progressFile) -join " ")
            }
            catch [System.IO.IOException] {
                $progress = $lastProgress
            }
            if ($progress -ne $lastProgress) {
                Write-Host "[progress] $progress"
                $lastProgress = $progress
            }
        }
        Start-Sleep -Seconds 3
    }

    if ($markerFailure.Length -gt 0) {
        Stop-BenchmarkProcessTree -Process $process
        throw "Host-probe benchmark aborted on $markerFailure. See $consoleLog"
    }
    if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
        Stop-BenchmarkProcessTree -Process $process
        throw "Host-probe benchmark did not produce $resultPath within $TimeoutSeconds s. See $consoleLog"
    }
    $result = Read-PropertiesFile -Path $resultPath
    $status = if ($result.ContainsKey("status")) { $result["status"] } else { "MISSING" }
    Write-Host "[result] status=$status"

    if (-not $process.WaitForExit(60000)) {
        $forcedCleanup = $true
        Stop-BenchmarkProcessTree -Process $process
    }
}
finally {
    Stop-BenchmarkProcessTree -Process $process
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $env:APPDATA = $previousAppData
    $env:LOCALAPPDATA = $previousLocalAppData

    # Evidence: the workload report, the plugin result, the whole plugin state
    # directory (it holds per-leg files), the run envelope and every log. Copied
    # before the digest checks so a failed verification cannot erase the numbers.
    if (Test-Path -LiteralPath $pluginStateRoot -PathType Container) {
        Copy-Item -LiteralPath $pluginStateRoot -Destination (Join-Path $evidencePath "plugin-state") -Recurse -Force
    }
    foreach ($file in @((Join-Path $runRoot "run.properties"), $consoleLog)) {
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            Copy-Item -LiteralPath $file -Destination (Join-Path $evidencePath (Split-Path -Leaf $file)) -Force
        }
    }
    if (Test-Path -LiteralPath $logsRoot -PathType Container) {
        Copy-Item -LiteralPath $logsRoot -Destination (Join-Path $evidencePath "logs") -Recurse -Force
    }
    if (-not [string]::IsNullOrWhiteSpace($recordOutputFull) -and
        (Test-Path -LiteralPath $recordOutputFull -PathType Leaf)) {
        Copy-Item -LiteralPath $recordOutputFull -Destination (Join-Path $evidencePath "human-trace.tsv") -Force
    }

    $sourceHashAfter = if ([string]::IsNullOrEmpty($sourceHash)) {
        "not-computed"
    }
    else { (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceProject).Hash.ToLowerInvariant() }
    $sourceUnchanged = [string]::IsNullOrEmpty($sourceHash) -or ($sourceHashAfter -eq $sourceHash)
    $recordHash = if (-not [string]::IsNullOrWhiteSpace($recordOutputFull) -and
        (Test-Path -LiteralPath $recordOutputFull -PathType Leaf)) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $recordOutputFull).Hash.ToLowerInvariant()
    }
    else { "not-computed" }
    $runEnvelope = @(
        "runId=$runId",
        "workload=$Workload",
        "variant=$Variant",
        "recordOutput=$recordOutputFull",
        "recordOutputSha256=$recordHash",
        ("iterations=" + $effectiveIterations),
        ("calibration=" + $Calibration.ToString().ToLowerInvariant()),
        ("legs=" + ($legNames -join ",")),
        "status=$status",
        "markerFailure=$markerFailure",
        ("forcedCleanup=" + $forcedCleanup.ToString().ToLowerInvariant()),
        "sourceSha256Before=$sourceHash",
        "sourceSha256After=$sourceHashAfter",
        ("sourceUnchanged=" + $sourceUnchanged.ToString().ToLowerInvariant()),
        ("projectCopySha256=" + $copyHash),
        ("hostJarSha256=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $hostJar).Hash.ToLowerInvariant()),
        "officialLauncherSource=$officialLauncher",
        "officialLauncherKind=$($launcherChoice.Reason)",
        ("officialLauncherSha256=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $officialLauncher).Hash.ToLowerInvariant()),
        "stagedLauncher=$launchBat",
        ("stagedLauncherSha256=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $launchBat).Hash.ToLowerInvariant()),
        ("stagedLauncherDp0Replacements=" + $dp0Replacements),
        ("agentSha256=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $agentSource).Hash.ToLowerInvariant()),
        ("exerciserSha256=" + (Get-FileHash -Algorithm SHA256 -LiteralPath $exerciserSource).Hash.ToLowerInvariant()),
        "evidence=$evidencePath",
        "runRoot=$runRoot"
    ) -join "`n"
    Write-Utf8NoBom -Path (Join-Path $evidencePath "run-envelope.properties") -Text ($runEnvelope + "`n")
    Write-Host "[evidence] $evidencePath"

    if (-not $sourceUnchanged) {
        Write-Error "The source project changed during the run: before=$sourceHash after=$sourceHashAfter"
    }
}

$reportStatus = "MISSING"
if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
    $report = Read-PropertiesFile -Path $reportPath
    if ($report.ContainsKey("status")) { $reportStatus = $report["status"] }
}
Write-Host "[result] ${reportName} status=$reportStatus"
if ($status -ne "PASS" -or $reportStatus -ne "PASS" -or $forcedCleanup) {
    Write-Error ("Host-probe benchmark failed: result=$status report=$reportStatus status=$reportStatus " +
        "forcedCleanup=$forcedCleanup evidence=$evidencePath")
    exit 1
}
Write-Host "[Turboism] $Workload benchmark passed. Evidence: $evidencePath" -ForegroundColor Green
exit 0
