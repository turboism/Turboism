# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [Parameter(Mandatory = $true)]
    [string]$HomePath,
    [string]$Java = ""
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")

function Test-TurboismJava17 {
    param([string]$Candidate)
    if ([string]::IsNullOrWhiteSpace($Candidate) -or -not (Test-CubismNormalFile $Candidate)) {
        return $false
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = [System.IO.Path]::GetFullPath($Candidate)
    $psi.Arguments = "-version"
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($name in @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
        if ($psi.EnvironmentVariables.ContainsKey($name)) { $psi.EnvironmentVariables.Remove($name) }
    }
    try {
        $process = [System.Diagnostics.Process]::Start($psi)
        if (-not $process.WaitForExit(15000)) {
            try { $process.Kill() } catch { }
            return $false
        }
        $versionText = $process.StandardError.ReadToEnd() + "`n" + $process.StandardOutput.ReadToEnd()
        if ($process.ExitCode -ne 0) { return $false }
        $match = [regex]::Match($versionText, '(?m)version\s+"(?<major>[0-9]+)(?:\.|\")')
        return $match.Success -and [int]$match.Groups["major"].Value -ge 17
    }
    catch { return $false }
    finally { if ($null -ne $process) { $process.Dispose() } }
}

function Find-TurboismInstallerJava {
    param([string]$ExplicitJava, [string]$TurboismHome)
    $candidates = [System.Collections.Generic.List[string]]::new()
    $seen = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = {
        param([string]$Candidate)
        if ([string]::IsNullOrWhiteSpace($Candidate)) { return }
        try { $full = [System.IO.Path]::GetFullPath($Candidate) } catch { return }
        if ($seen.Add($full)) { [void]$candidates.Add($full) }
    }
    & $add $ExplicitJava
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        & $add (Join-Path $env:JAVA_HOME "bin\java.exe")
    }
    try {
        $command = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
        if ($null -ne $command) { & $add $command.Source }
    }
    catch { }

    $statePath = Join-Path $TurboismHome "cubism-installations.json"
    if (Test-CubismNormalFile $statePath) {
        try {
            $state = Read-CubismInstallationState -StatePath $statePath
            if ($state.Valid) {
                foreach ($entry in @($state.Installations | Where-Object { $_.Selected })) {
                    & $add (Join-Path $entry.Root "app\jre\bin\java.exe")
                }
            }
        }
        catch { }
    }
    try {
        $discovery = Get-CubismDiscoveryRoots
        foreach ($candidate in Get-CubismInstallations -Roots $discovery) {
            if ($candidate.Selectable) { & $add $candidate.Java }
        }
    }
    catch { }

    foreach ($candidate in $candidates) {
        if (Test-TurboismJava17 $candidate) { return $candidate }
    }
    return ""
}

try {
    $home = [System.IO.Path]::GetFullPath($HomePath).TrimEnd('\', '/')
    if (-not (Test-CubismNormalDirectory $home)) {
        throw "Turboism home is not an existing ordinary directory: $home"
    }
    $agent = Join-Path $home "turboism-agent.jar"
    if (-not (Test-CubismNormalFile $agent)) {
        throw "Turboism agent is missing: $agent"
    }
    $javaExe = Find-TurboismInstallerJava -ExplicitJava $Java -TurboismHome $home
    if ([string]::IsNullOrWhiteSpace($javaExe)) {
        throw "No trusted Java 17 or newer runtime is available. Install a supported Cubism Editor or Java 17+, then retry."
    }

    Write-Host "Installing the pinned Turboism-managed GraalVM runtime with: $javaExe"
    $savedOptions = @{}
    foreach ($name in @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
        $savedOptions[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
    try {
        & $javaExe -cp $agent dev.turboism.graal.ManagedGraalRuntimeCli install $home
        exit $LASTEXITCODE
    }
    finally {
        foreach ($name in $savedOptions.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedOptions[$name], "Process")
        }
    }
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
