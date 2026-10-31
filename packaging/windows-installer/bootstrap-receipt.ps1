# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [string]$HomePath = "",
    [string]$StagePath = "",
    [string]$JavaPath = "",
    [string]$DiscoverySnapshot = "",
    [string]$DiscoverySnapshotSha256 = "",
    [string]$WorkPath = "",
    [string]$Classpath = "",
    [string]$HelperClass = "dev.turboism.update.FrameworkUpdateHelper",
    [switch]$Strict,
    [switch]$PassThru
)

# Turboism installation receipt bootstrap helper.
#
# Connects existing FrameworkUpdateHelper --initialize-receipt into the
# first conventional full install / standard ZIP extraction workflow.
# Verifies the independent signature and complete installed layout, then
# initializes immutable receipt documents and a root-bound index.
#
# If no signed bootstrap documents are present (legacy package), it exits
# cleanly without creating a receipt or claiming self-update is available.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = $MyInvocation.MyCommand.Path
$scriptDir = Split-Path -Parent $scriptPath

# Dot-source common launcher utilities if available
$commonScript = Join-Path $scriptDir "cubism-launch-common.ps1"
if (Test-Path -LiteralPath $commonScript -PathType Leaf) {
    . $commonScript
}

function Test-Jdk17Runnable {
    param([string]$Java)
    if ([string]::IsNullOrWhiteSpace($Java) -or -not (Test-Path -LiteralPath $Java -PathType Leaf)) {
        return $false
    }
    $prev = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Java -version 2>&1
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) { return $false }
        $text = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
        return $text -match 'version "(?:1[7-9]|[2-9][0-9])\.'
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Find-RunnableJava {
    param(
        [string]$ExplicitJava,
        [string]$TurboismHome,
        [string]$SnapshotPath
    )

    $candidates = New-Object System.Collections.Generic.List[string]

    # 1. Explicit override
    if (-not [string]::IsNullOrWhiteSpace($ExplicitJava)) {
        [void]$candidates.Add($ExplicitJava)
    }

    # 2. Discovery snapshot from installer (Cubism bundled JRE)
    if (-not [string]::IsNullOrWhiteSpace($SnapshotPath) -and (Test-Path -LiteralPath $SnapshotPath -PathType Leaf)) {
        try {
            $json = Get-Content -LiteralPath $SnapshotPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($null -ne $json.candidates) {
                foreach ($c in $json.candidates) {
                    if (-not [string]::IsNullOrWhiteSpace($c.Java)) { [void]$candidates.Add($c.Java) }
                    elseif (-not [string]::IsNullOrWhiteSpace($c.Root)) {
                        [void]$candidates.Add((Join-Path $c.Root "app\jre\bin\java.exe"))
                    }
                }
            }
        } catch { }
    }

    # 3. State-configured Cubism installation JRE
    $statePath = Join-Path $TurboismHome "state\runtime\cubism-selection.json"
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        try {
            $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($null -ne $state.candidates) {
                foreach ($c in $state.candidates) {
                    if ($c.Selected -and -not [string]::IsNullOrWhiteSpace($c.Java)) {
                        [void]$candidates.Add($c.Java)
                    } elseif ($c.Selected -and -not [string]::IsNullOrWhiteSpace($c.Root)) {
                        [void]$candidates.Add((Join-Path $c.Root "app\jre\bin\java.exe"))
                    }
                }
            }
        } catch { }
    }

    # 4. Managed GraalVM runtime in Turboism home
    [void]$candidates.Add((Join-Path $TurboismHome "graal\runtime\bin\java.exe"))
    [void]$candidates.Add((Join-Path $TurboismHome "graalvm\bin\java.exe"))

    # 5. Environment overrides
    if (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_GRAALVM_HOME)) {
        [void]$candidates.Add((Join-Path $env:TURBOISM_GRAALVM_HOME "bin\java.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:GRAALVM_HOME)) {
        [void]$candidates.Add((Join-Path $env:GRAALVM_HOME "bin\java.exe"))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        [void]$candidates.Add((Join-Path (Join-Path $env:JAVA_HOME "bin") "java.exe"))
        [void]$candidates.Add((Join-Path (Join-Path $env:JAVA_HOME "bin") "java"))
    }

    # 6. Standard system PATH
    $pathCmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $pathCmd -and -not [string]::IsNullOrWhiteSpace($pathCmd.Source)) {
        [void]$candidates.Add($pathCmd.Source)
    }
    $pathCmdLinux = Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $pathCmdLinux -and -not [string]::IsNullOrWhiteSpace($pathCmdLinux.Source)) {
        [void]$candidates.Add($pathCmdLinux.Source)
    }

    foreach ($candidate in $candidates) {
        if (Test-Jdk17Runnable $candidate) {
            return $candidate
        }
    }
    return $null
}

function Find-BootstrapDocuments {
    param(
        [string]$ExplicitStage,
        [string]$TurboismHome
    )

    $searchDirs = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($ExplicitStage)) {
        [void]$searchDirs.Add($ExplicitStage)
    }
    [void]$searchDirs.Add((Join-Path $TurboismHome "state\runtime\update\bootstrap"))
    [void]$searchDirs.Add((Join-Path $TurboismHome "state\runtime\update\stage"))
    [void]$searchDirs.Add((Join-Path $TurboismHome "bootstrap"))
    [void]$searchDirs.Add($TurboismHome)

    foreach ($dir in $searchDirs) {
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
            continue
        }
        # Check standard manifest.json and manifest.json.sig
        $m1 = Join-Path $dir "manifest.json"
        $s1 = Join-Path $dir "manifest.json.sig"
        if ((Test-Path -LiteralPath $m1 -PathType Leaf) -and (Test-Path -LiteralPath $s1 -PathType Leaf)) {
            return [pscustomobject]@{
                Manifest = $m1
                Signature = $s1
                Directory = $dir
            }
        }
        # Check versioned prefix e.g. turboism-*-manifest.json and .sig
        $manifests = @(Get-ChildItem -LiteralPath $dir -Filter "*manifest.json" -File | Where-Object { $_.Name -ne "manifest.json" })
        foreach ($mf in $manifests) {
            $sigPath = $mf.FullName + ".sig"
            if (Test-Path -LiteralPath $sigPath -PathType Leaf) {
                return [pscustomobject]@{
                    Manifest = $mf.FullName
                    Signature = $sigPath
                    Directory = $dir
                }
            }
        }
    }
    return $null
}

# 1. Resolve and validate Turboism Home
if (-not [string]::IsNullOrWhiteSpace($HomePath)) {
    $turboismHome = [System.IO.Path]::GetFullPath($HomePath.TrimEnd('\', '/'))
} elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) {
    $turboismHome = [System.IO.Path]::GetFullPath($env:TURBOISM_HOME.TrimEnd('\', '/'))
} else {
    $turboismHome = [System.IO.Path]::GetFullPath($scriptDir)
}

if (-not [System.IO.Directory]::Exists($turboismHome)) {
    throw "Turboism home directory does not exist: $turboismHome"
}

# 2. Check for bootstrap documents
$docs = Find-BootstrapDocuments -ExplicitStage $StagePath -TurboismHome $turboismHome
if ($null -eq $docs) {
    if ($Strict) {
        throw "Bootstrap receipt documents (manifest.json and manifest.json.sig) not found in $turboismHome or stage path."
    }
    Write-Host "[bootstrap-receipt] No bootstrap documents found; self-update receipt initialization skipped."
    if ($PassThru) {
        return [pscustomobject]@{
            Success = $true
            Status = "SKIPPED_NO_DOCUMENTS"
            ExitCode = 0
            ReceiptPath = $null
        }
    }
    exit 0
}

# 3. Resolve runnable JDK 17+
$java = Find-RunnableJava -ExplicitJava $JavaPath -TurboismHome $turboismHome -SnapshotPath $DiscoverySnapshot
if ($null -eq $java) {
    if ($Strict) {
        throw "No runnable JDK 17+ or Cubism JRE found to initialize self-update receipt."
    }
    Write-Warning "[bootstrap-receipt] No runnable JDK 17+ found; self-update receipt initialization deferred."
    if ($PassThru) {
        return [pscustomobject]@{
            Success = $true
            Status = "DEFERRED_NO_JAVA"
            ExitCode = 0
            ReceiptPath = $null
        }
    }
    exit 0
}

# 4. Resolve classpath and work directory
$cp = if (-not [string]::IsNullOrWhiteSpace($Classpath)) {
    $Classpath
} else {
    Join-Path $turboismHome "turboism-agent.jar"
}

$workDir = if (-not [string]::IsNullOrWhiteSpace($WorkPath)) {
    $WorkPath
} else {
    Join-Path $turboismHome "state\runtime\update\helper"
}

# 5. Prepare normalized staging directory with exact manifest.json & manifest.json.sig
$tempStage = $null
$effectiveStage = ""
try {
    if ((Split-Path -Leaf $docs.Manifest) -eq "manifest.json" -and
        (Split-Path -Leaf $docs.Signature) -eq "manifest.json.sig" -and
        (Split-Path -Parent $docs.Manifest) -eq (Split-Path -Parent $docs.Signature) -and
        (Split-Path -Parent $docs.Manifest) -ne $turboismHome) {
        $effectiveStage = Split-Path -Parent $docs.Manifest
    } else {
        $tempStage = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-receipt-stage-" + [System.Guid]::NewGuid().ToString("N"))
        [void][System.IO.Directory]::CreateDirectory($tempStage)
        Copy-Item -LiteralPath $docs.Manifest -Destination (Join-Path $tempStage "manifest.json")
        Copy-Item -LiteralPath $docs.Signature -Destination (Join-Path $tempStage "manifest.json.sig")
        $effectiveStage = $tempStage
    }

    # 6. Invoke FrameworkUpdateHelper --initialize-receipt
    Write-Host "[bootstrap-receipt] Initializing framework receipt using $java ..."
    $helperArgs = @(
        "-cp", $cp,
        $HelperClass,
        "--initialize-receipt",
        "--root", $turboismHome,
        "--stage", $effectiveStage,
        "--work", $workDir
    )

    $prevEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $helperOutput = & $java $helperArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $outputText = ($helperOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine

    # Exit code 10 (EXIT_NOTHING) or 0 indicates successful receipt initialization
    if ($exitCode -eq 10 -or $exitCode -eq 0) {
        Write-Host "[bootstrap-receipt] Framework update receipt initialized successfully."
        $receiptFile = Join-Path $turboismHome "state\runtime\update\installed-receipt.json"
        if ($PassThru) {
            return [pscustomobject]@{
                Success = $true
                Status = "INITIALIZED"
                ExitCode = 0
                HelperExitCode = $exitCode
                ReceiptPath = $receiptFile
                Output = $outputText
            }
        }
        exit 0
    }

    # Non-success outcomes from helper:
    # 70: Rejected (verification/signature/policy)
    # 74: Failed (modified payload, different receipt already exists, or IO failure)
    # 75: Live Cubism process still running
    # 64: Usage error
    if ($PassThru) {
        return [pscustomobject]@{
            Success = $false
            Status = "FAILED"
            ExitCode = $exitCode
            HelperExitCode = $exitCode
            ReceiptPath = $null
            Output = $outputText
        }
    }
    [System.Console]::Error.WriteLine("[bootstrap-receipt] Framework receipt initialization failed (helper exit code $exitCode):`n$outputText")
    exit $exitCode
} finally {
    if ($null -ne $tempStage -and (Test-Path -LiteralPath $tempStage)) {
        try { Remove-Item -LiteralPath $tempStage -Recurse -Force -ErrorAction SilentlyContinue } catch { }
    }
}
