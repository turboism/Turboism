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
    [string[]]$HelperPrefixArgs = @(),
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
if (-not (Test-Path -LiteralPath $commonScript -PathType Leaf)) {
    $commonScript = Join-Path $turboismHome "cubism-launch-common.ps1"
}
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
        [string]$SnapshotPath,
        [string]$SnapshotSha256
    )

    # 1. Prioritize explicit Java from trusted caller
    if (-not [string]::IsNullOrWhiteSpace($ExplicitJava)) {
        if (Test-Jdk17Runnable $ExplicitJava) {
            return $ExplicitJava
        }
    }

    # 2. Selected candidate from real cubism-installations.json using Read-CubismInstallationState
    $stateFile = Join-Path $TurboismHome "cubism-installations.json"
    if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
        try {
            $state = Read-CubismInstallationState -StatePath $stateFile
            if ($state.Valid -and $null -ne $state.Installations) {
                foreach ($inst in @($state.Installations | Where-Object { $_.Selected })) {
                    if (Get-Command New-CubismInstallationCandidate -ErrorAction SilentlyContinue) {
                        $candidate = New-CubismInstallationCandidate -Root $inst.Root -TurboismHome $TurboismHome
                        if (-not [string]::IsNullOrWhiteSpace($candidate.Java) -and (Test-Jdk17Runnable $candidate.Java)) {
                            return $candidate.Java
                        }
                    } else {
                        $candJava = Join-Path $inst.Root "app\jre\bin\java.exe"
                        if (Test-Jdk17Runnable $candJava) {
                            return $candJava
                        }
                    }
                }
            }
        } catch { }
    }

    # 3. Verified installer discovery snapshot using Read-CubismInstallerSnapshot
    if (-not [string]::IsNullOrWhiteSpace($SnapshotPath) -and -not [string]::IsNullOrWhiteSpace($SnapshotSha256)) {
        try {
            $snapshotCandidates = @(Read-CubismInstallerSnapshot -TurboismHome $TurboismHome -SnapshotPath $SnapshotPath -ExpectedSha256 $SnapshotSha256)
            foreach ($c in $snapshotCandidates) {
                if (-not [string]::IsNullOrWhiteSpace($c.Java) -and (Test-Jdk17Runnable $c.Java)) {
                    return $c.Java
                }
            }
        } catch { }
    }

    # 4. Managed GraalVM runtime in Turboism home
    foreach ($graalRel in @("graal\runtime\bin\java.exe", "graalvm\bin\java.exe")) {
        $graalJava = Join-Path $TurboismHome $graalRel
        if (Test-Jdk17Runnable $graalJava) {
            return $graalJava
        }
    }

    # 5. Environment overrides
    foreach ($envVar in @($env:TURBOISM_GRAALVM_HOME, $env:GRAALVM_HOME, $env:JAVA_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($envVar)) {
            foreach ($exe in @("bin\java.exe", "bin\java")) {
                $candidate = Join-Path $envVar $exe
                if (Test-Jdk17Runnable $candidate) {
                    return $candidate
                }
            }
        }
    }

    # 6. Standard system PATH
    $pathCmd = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $pathCmd -and -not [string]::IsNullOrWhiteSpace($pathCmd.Source)) {
        if (Test-Jdk17Runnable $pathCmd.Source) { return $pathCmd.Source }
    }
    $pathCmdLinux = Get-Command java -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $pathCmdLinux -and -not [string]::IsNullOrWhiteSpace($pathCmdLinux.Source)) {
        if (Test-Jdk17Runnable $pathCmdLinux.Source) { return $pathCmdLinux.Source }
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
    [void]$searchDirs.Add((Join-Path $TurboismHome "bootstrap"))
    [void]$searchDirs.Add((Join-Path $TurboismHome "state\runtime\update\bootstrap"))
    [void]$searchDirs.Add((Join-Path $TurboismHome "state\runtime\update\stage"))
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

function Initialize-PrivateStateNamespace {
    param([string]$TurboismHome)

    # FrameworkUpdateReceipt requires that the receipt state directories
    # (root/state, root/state/runtime, root/state/runtime/update) have owner-only
    # access (plus SYSTEM and Administrators on Windows).
    # Must NOT follow junctions, must verify owner matches root, and must purge all other explicit/inherited ACEs.
    if ($env:OS -ne "Windows_NT") {
        return
    }

    $rootItem = Get-Item -LiteralPath $TurboismHome -Force
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Turboism root directory is a reparse point: $TurboismHome"
    }
    $rootInfo = New-Object System.IO.DirectoryInfo($TurboismHome)
    $rootAcl = $rootInfo.GetAccessControl([System.Security.AccessControl.AccessControlSections]::Owner)
    $rootOwner = $rootAcl.GetOwner([System.Security.Principal.SecurityIdentifier])

    $stateRoot = Join-Path $TurboismHome "state"
    $runtimeDir = Join-Path $stateRoot "runtime"
    $updateDir = Join-Path $runtimeDir "update"

    foreach ($dir in @($stateRoot, $runtimeDir, $updateDir)) {
        if (Test-Path -LiteralPath $dir) {
            $dirItem = Get-Item -LiteralPath $dir -Force
            if (($dirItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "State directory is a reparse point or link: $dir"
            }
            if (-not $dirItem.PSIsContainer) {
                throw "State path is not a directory: $dir"
            }
            $dirInfo = New-Object System.IO.DirectoryInfo($dir)
            $dirOwner = $dirInfo.GetAccessControl([System.Security.AccessControl.AccessControlSections]::Owner).GetOwner([System.Security.Principal.SecurityIdentifier])
            if (-not $dirOwner.Equals($rootOwner)) {
                throw "State directory owner does not match root owner: $dir"
            }
        } else {
            [void][System.IO.Directory]::CreateDirectory($dir)
            $dirInfo = New-Object System.IO.DirectoryInfo($dir)
        }

        # Build complete allowlist DACL: owner, SYSTEM, Administrators FullControl, no other ACEs
        $newAcl = New-Object System.Security.AccessControl.DirectorySecurity
        $newAcl.SetAccessRuleProtection($true, $false)
        $newAcl.SetOwner($rootOwner)
        $allowed = @($rootOwner)
        foreach ($sidStr in @("S-1-5-18", "S-1-5-32-544")) {
            try { $allowed += New-Object System.Security.Principal.SecurityIdentifier($sidStr) } catch { }
        }
        foreach ($sid in $allowed) {
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                $sid,
                [System.Security.AccessControl.FileSystemRights]::FullControl,
                [System.Security.AccessControl.InheritanceFlags]"ContainerInherit, ObjectInherit",
                [System.Security.AccessControl.PropagationFlags]::None,
                [System.Security.AccessControl.AccessControlType]::Allow
            )
            $newAcl.AddAccessRule($rule)
        }
        $dirInfo.SetAccessControl($newAcl)
    }
}

if ($MyInvocation.InvocationName -ne ".") {
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

# 2. In non-strict (auto/first-launch) mode, check if a receipt already exists.
# An upgraded installation must not re-run stale bootstrap documents from a previous release.
$receiptFile = Join-Path $turboismHome "state\runtime\update\installed-receipt.json"
if (-not $Strict -and (Test-Path -LiteralPath $receiptFile -PathType Leaf)) {
    try {
        $receiptItem = Get-Item -LiteralPath $receiptFile -Force -ErrorAction Stop
        if ($receiptItem.Length -gt 0) {
            Write-Host "[bootstrap-receipt] Existing installation receipt detected; skipping bootstrap receipt initialization."
            if ($PassThru) {
                return [pscustomobject]@{
                    Success = $true
                    Status = "SKIPPED_EXISTING_RECEIPT"
                    ExitCode = 0
                    ReceiptPath = $receiptFile
                }
            }
            exit 0
        }
    } catch { }
}

# 3. Check for bootstrap documents
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

# 4. Resolve runnable JDK 17+
$java = Find-RunnableJava -ExplicitJava $JavaPath -TurboismHome $turboismHome -SnapshotPath $DiscoverySnapshot -SnapshotSha256 $DiscoverySnapshotSha256
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

# 5. Resolve classpath and work directory
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

# 6. Ensure state namespace directories have owner-restricted ACL before helper checks requirePrivate
Initialize-PrivateStateNamespace -TurboismHome $turboismHome

# 7. Prepare normalized staging directory with exact manifest.json & manifest.json.sig
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

    # 8. Invoke FrameworkUpdateHelper --initialize-receipt
    Write-Host "[bootstrap-receipt] Initializing framework receipt using $java ..."
    $helperArgs = @(
        "-cp", $cp,
        $HelperClass
    ) + $HelperPrefixArgs + @(
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
}
