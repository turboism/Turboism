# Synthetic launch-lease regression gate.
#
# Part 1 exercises the production helper/token functions through a mock argument-echo executable.
# Part 2 runs the REAL launcher (launch-cubism-turboism.ps1) end to end against an isolated
# TURBOISM_HOME plus fake Cubism-shaped roots, so the prepare/launch/release orchestration is the
# production control flow, not a copy of it. Only the Java helper/host verdict is faked.
#
# Nothing here discovers or starts an installed Cubism host, reads or writes the current user's
# Start Menu / shortcut state, or invokes the real Java helper. These mocks are explicitly labeled
# and are NOT real Cubism acceptance; real helper/lease byte-level behavior lives in the Java
# process tests owned by another worker.
[CmdletBinding()]
param([switch]$SkipRealLauncher)
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")
Set-StrictMode -Version 3.0
# This gate builds its synthetic helper/host as a real console executable, which requires Windows
# PowerShell 5.1: Add-Type -OutputType ConsoleApplication is not supported in PowerShell 7.
if ($PSVersionTable.PSVersion.Major -gt 5) {
    throw 'test-launch-lease.ps1 requires Windows PowerShell 5.1 (PowerShell 7 cannot build the synthetic console helper).'
}
$script:LeaseAssertions = 0
$script:PowerShellExe = (Get-Process -Id $PID).Path

function Assert-Lease {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:LeaseAssertions++
    Write-Host "ok: $Message"
}

function Get-LeaseMarkerText {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return [System.IO.File]::ReadAllText($Path) }
    return ""
}

function Clear-LeaseMarker {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path -PathType Leaf) { Remove-Item -LiteralPath $Path -Force }
}

function Get-LeaseArgvRecord {
    param([string]$Path, [string]$Containing = "")
    # The synthetic host appends one block per invocation to an explicitly UTF-8 file, each argument
    # as the hex of its UTF-16 code units. Exact argv checks never depend on cmd's `echo` bytes.
    $empty = [pscustomobject]@{ Values = @() }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $empty }
    $blocks = New-Object System.Collections.Generic.List[object]
    $current = $null
    foreach ($line in [System.IO.File]::ReadAllLines($Path, (New-Object System.Text.UTF8Encoding($false)))) {
        if ($line -eq '=== invocation ===') {
            if ($null -ne $current) { [void]$blocks.Add([pscustomobject]@{ Values = @($current) }) }
            $current = New-Object System.Collections.Generic.List[string]
            continue
        }
        if ($null -eq $current) { continue }
        if ($line -match '^argv=[0-9]+$') { continue }
        if ($line.Length -gt 0 -and $line.Length % 4 -eq 0 -and $line -match '^[0-9a-f]+$') {
            $bytes = New-Object byte[] ($line.Length / 2)
            for ($index = 0; $index -lt $bytes.Length; $index++) {
                $bytes[$index] = [Convert]::ToByte($line.Substring($index * 2, 2), 16)
            }
            [void]$current.Add([System.Text.Encoding]::Unicode.GetString($bytes))
        }
    }
    if ($null -ne $current) { [void]$blocks.Add([pscustomobject]@{ Values = @($current) }) }
    if ($blocks.Count -eq 0) { return $empty }
    if (-not [string]::IsNullOrWhiteSpace($Containing)) {
        $matches = @($blocks | Where-Object { @($_.Values | Where-Object { $_ -ceq $Containing }).Count -gt 0 })
        if ($matches.Count -eq 0) { return $empty }
        return $matches[$matches.Count - 1]
    }
    return $blocks[$blocks.Count - 1]
}

function Compare-LeaseArgv {
    param([string[]]$Actual, [string[]]$Expected)
    # Returns an empty string on an exact match, otherwise a concrete first-difference message.
    $actualValues = @($Actual)
    if ($actualValues.Count -ne @($Expected).Count) {
        return ("argv count {0} != expected {1}: [{2}]" -f $actualValues.Count, @($Expected).Count, ($actualValues -join ' | '))
    }
    for ($index = 0; $index -lt @($Expected).Count; $index++) {
        if ($actualValues[$index] -cne $Expected[$index]) {
            return ("argv[{0}] actual <{1}> != expected <{2}>" -f $index, $actualValues[$index], $Expected[$index])
        }
    }
    return ""
}

function New-LeaseCubismFixture {
    param([string]$Root, [string]$Version, [bool]$WithoutEditorMainClass = $false)
    New-Item -ItemType Directory -Path (Join-Path $Root "app\jre\bin"), (Join-Path $Root "app\lib") -Force | Out-Null
    $batLines = @(
        '@echo off',
        'cd /d "%~dp0"',
        'set JAVA_EXE=app\jre\bin\java.cmd',
        '>>"%TURBOISM_TEST_OUTPUT%" echo NORMAL=1',
        '>>"%TURBOISM_TEST_OUTPUT%" echo CURRENT=%CD%',
        '%JAVA_EXE% ^',
        '  -showversion ^',
        '  com.live2d.cubism.CECubismEditorApp ^',
        '  "%~f1"',
        'exit /b %ERRORLEVEL%'
    )
    if ($WithoutEditorMainClass) {
        # Admission still passes (same files), but the managed options BAT cannot be built. That is a
        # genuine pre-spawn failure raised by production code after the lease was prepared.
        $batLines = @($batLines | Where-Object { $_ -notlike '*CECubismEditorApp*' })
    }
    Set-Content -LiteralPath (Join-Path $Root "CubismEditor5.bat") -Encoding ASCII -Value $batLines
    Set-Content -LiteralPath (Join-Path $Root "app\jre\bin\java.cmd") -Encoding ASCII -Value @(
        '@echo off',
        '>>"%TURBOISM_TEST_OUTPUT%" echo JAVA_ARGS=%*',
        '>&2 echo CHILD_STDERR: synthetic child diagnostic',
        'if defined TURBOISM_TEST_CHILD_EXIT exit /b %TURBOISM_TEST_CHILD_EXIT%',
        'exit /b 23'
    )
    Copy-Item -LiteralPath $script:SyntheticProbe -Destination (Join-Path $Root "app\jre\bin\java.exe") -Force
    $applicationJar = Join-Path $Root "app\lib\Live2D_Cubism.jar"
    [System.IO.File]::WriteAllBytes($applicationJar, [byte[]](5, 6, 7, 8))
    $probeResult = [ordered]@{
        schemaVersion = 2
        identity = @{ version = $Version }
        runtimeAdmitted = $true
        status = "COMPATIBLE"
        reason = "Synthetic launch-lease fixture; not actual artifact verification."
    }
    [System.IO.File]::WriteAllText($applicationJar + ".fixture-probe.json",
        ($probeResult | ConvertTo-Json -Depth 4 -Compress), (New-Object System.Text.UTF8Encoding($false)))
    return $Root
}

function Invoke-RealLauncherInUtf8Console {
    param([string[]]$LauncherArguments)
    # Runs the real launcher in its OWN hidden console whose code page is forced to UTF-8. The chcp
    # happens inside that child console only: the caller's shared console code page is never touched.
    $launcher = Join-Path $scriptDir "launch-cubism-turboism.ps1"
    $argumentText = (@($LauncherArguments) | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join ' '
    $commandLine = 'chcp 65001 >nul & "' + $script:PowerShellExe + '" -NoProfile -ExecutionPolicy Bypass -File "' + $launcher + '" ' + $argumentText
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/c', $commandLine) -Wait -PassThru -WindowStyle Hidden
    return [pscustomobject]@{ ExitCode = [int]$process.ExitCode; Output = "(run inside a hidden UTF-8 console)" }
}

function Invoke-RealLauncher {
    param([string[]]$LauncherArguments)
    # The launcher is a separate process, so no in-process test seam can leak into it. Its only
    # fakes are the synthetic probe/helper executable and the fixture BATs inside this run's temp root.
    $output = @()
    $exitCode = -1
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:PowerShellExe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $scriptDir "launch-cubism-turboism.ps1") @LauncherArguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousErrorPreference }
    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        Output = (@($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine)
    }
}

$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-lease-" + [guid]::NewGuid().ToString("N"))
$temp = [System.IO.Path]::GetFullPath($temp)
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$turboismHome = Join-Path $temp "Turboism home 空间"
New-Item -ItemType Directory -Path $turboismHome -Force | Out-Null
$agent = Join-Path $turboismHome "turboism-agent.jar"
[System.IO.File]::WriteAllBytes($agent, [byte[]](9, 8, 7, 6))
$marker = Join-Path $temp "lease-marker.txt"
$stageDir = Join-Path $turboismHome 'state\managed-launch'
$savedEnvironment = @{
    TURBOISM_TEST_OUTPUT = $env:TURBOISM_TEST_OUTPUT
    TURBOISM_TEST_HELPER_EXIT = $env:TURBOISM_TEST_HELPER_EXIT
    TURBOISM_TEST_CHILD_EXIT = $env:TURBOISM_TEST_CHILD_EXIT
    TURBOISM_CUBISM_JAVA = $env:TURBOISM_CUBISM_JAVA
    TURBOISM_GRAALVM_HOME = $env:TURBOISM_GRAALVM_HOME
    GRAALVM_HOME = $env:GRAALVM_HOME
}
$env:TURBOISM_TEST_OUTPUT = $marker

try {
    # ---------------------------------------------------------------- token generation
    $tokenA = New-TurboismLaunchToken
    $tokenB = New-TurboismLaunchToken
    Assert-Lease ($tokenA -match '^[a-f0-9]{64}$') "launch token is 64 lowercase hex"
    Assert-Lease ($tokenA -ne $tokenB) "two launch tokens are distinct"

    # ------------------------------------------- helper resolution (normal file / never overwrite)
    $updateWork = Join-Path $turboismHome 'state\runtime\update\helper'
    $updateJournal = Join-Path $updateWork 'journal.json'
    $updatePending = Join-Path $updateWork 'pending.json'
    New-Item -ItemType Directory -Path $updateWork -Force | Out-Null
    $helper1 = Resolve-TurboismLaunchHelper -TurboismHome $turboismHome -Agent $agent -UpdateJournal $updateJournal -UpdatePending $updatePending
    $helper2 = Resolve-TurboismLaunchHelper -TurboismHome $turboismHome -Agent $agent -UpdateJournal $updateJournal -UpdatePending $updatePending
    Assert-Lease $helper1.Temporary "ordinary helper is a temporary copy"
    Assert-Lease ($helper1.Jar -notlike '*helper-runner.jar') "ordinary helper never overwrites helper-runner.jar"
    Assert-Lease ($helper1.Jar -ne $helper2.Jar) "each ordinary launch uses a distinct helper copy"
    Assert-Lease (Test-Path -LiteralPath $helper1.Jar -PathType Leaf) "ordinary helper copy exists"
    Remove-Item -LiteralPath $helper1.Jar, $helper2.Jar -Force -ErrorAction SilentlyContinue

    # A retained helper that is not a normal file (directory junction/plain directory standing in for
    # the JAR) must fail closed instead of being used blindly.
    $retained = Join-Path $turboismHome 'state\runtime\update\helper-runner.jar'
    Set-Content -LiteralPath $updateJournal -Value '{"format":"turboism-update-journal-v1"}' -Encoding UTF8
    $retainedMissing = $false
    try { Resolve-TurboismLaunchHelper -TurboismHome $turboismHome -Agent $agent -UpdateJournal $updateJournal -UpdatePending $updatePending | Out-Null }
    catch { $retainedMissing = $true }
    Assert-Lease $retainedMissing "missing retained helper is fatal (no silent fallback)"
    New-Item -ItemType Directory -Path $retained -Force | Out-Null
    $retainedDirectoryRejected = $false
    try { Resolve-TurboismLaunchHelper -TurboismHome $turboismHome -Agent $agent -UpdateJournal $updateJournal -UpdatePending $updatePending | Out-Null }
    catch { $retainedDirectoryRejected = $true }
    Assert-Lease $retainedDirectoryRejected "a non-regular retained helper path is rejected"
    Remove-Item -LiteralPath $retained -Force -Recurse
    [System.IO.File]::WriteAllBytes($retained, [byte[]](1, 2, 3))
    $retainedSha = Get-CubismSha256 $retained
    $helper3 = Resolve-TurboismLaunchHelper -TurboismHome $turboismHome -Agent $agent -UpdateJournal $updateJournal -UpdatePending $updatePending
    Assert-Lease (-not $helper3.Temporary) "journal present keeps the retained helper"
    Assert-Lease ($helper3.Jar -eq $retained) "retained helper path is helper-runner.jar"
    Assert-Lease ((Get-CubismSha256 $retained) -eq $retainedSha) "a pending recovery helper is never replaced in place"
    $stageFilesAfterPending = @(Get-ChildItem -LiteralPath $stageDir -File -Force -ErrorAction SilentlyContinue)
    Assert-Lease ($stageFilesAfterPending.Count -eq 0) "pending recovery never creates a competing helper copy"
    Remove-Item -LiteralPath $updateJournal -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $retained -Force -ErrorAction SilentlyContinue

    # -------------------------------------------------- prepare/release plumbing (mock executable)
    # The mock writes a diagnostic line to stdout AND to stderr on every call, so a helper whose
    # chatter reaches the success pipeline would corrupt the integer return.
    $fakeJava = Join-Path $temp "synthetic-helper.cmd"
    Set-Content -LiteralPath $fakeJava -Encoding ASCII -Value @(
        '@echo off',
        'echo NOTHING: synthetic helper stdout diagnostic',
        '>&2 echo NOTHING_ERR: synthetic helper stderr diagnostic',
        '>>"%TURBOISM_TEST_OUTPUT%" echo HELPER_ARGS=%*',
        'if defined TURBOISM_TEST_HELPER_EXIT (exit /b %TURBOISM_TEST_HELPER_EXIT%)',
        'exit /b 10'
    )
    $env:TURBOISM_TEST_HELPER_EXIT = "10"
    $token = New-TurboismLaunchToken
    Clear-LeaseMarker $marker
    $prepareExit = Invoke-TurboismLaunchPrepare `
        -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
        -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchToken $token
    Assert-Lease ($prepareExit -is [int]) "prepare returns a single integer despite helper stdout/stderr"
    Assert-Lease ($prepareExit -eq 10) "prepare propagates the helper exit code"
    $prepareMarker = Get-LeaseMarkerText $marker
    Assert-Lease ($prepareMarker -match '--prepare-launch') "prepare invokes --prepare-launch"
    Assert-Lease ($prepareMarker -match [regex]::Escape('--launch-token ' + $token)) "prepare passes the exact launch token"
    Assert-Lease (([regex]::Matches($prepareMarker, [regex]::Escape($token))).Count -eq 1) "prepare passes the token exactly once"
    $leftoverHelpers = @(Get-ChildItem -LiteralPath $stageDir -Filter '.turboism-helper-*.jar' -ErrorAction SilentlyContinue)
    Assert-Lease ($leftoverHelpers.Count -eq 0) "prepare leaves no temporary helper JAR behind"

    Clear-LeaseMarker $marker
    $releaseExit = Invoke-TurboismLaunchRelease `
        -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
        -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchToken $token
    Assert-Lease ($releaseExit -is [int]) "release returns a single integer despite helper stdout/stderr"
    Assert-Lease ($releaseExit -eq 10) "release propagates the helper exit code"
    $releaseMarker = Get-LeaseMarkerText $marker
    Assert-Lease ($releaseMarker -match '--release-launch') "release invokes --release-launch"
    Assert-Lease ($releaseMarker -match [regex]::Escape('--launch-token ' + $token)) "release passes the exact launch token"

    # A native helper call that never starts must not inherit the previous allowed exit code.
    # $LASTEXITCODE is still the accepted 10 from the release above when the missing Java is invoked.
    Clear-LeaseMarker $marker
    Assert-Lease ($global:LASTEXITCODE -eq 10) "the previous helper call left an allowed native exit code behind"
    $missingJava = Join-Path $temp "missing-java.exe"
    $missingExit = $null
    $missingThrew = $false
    try {
        $missingExit = Invoke-TurboismLaunchPrepare `
            -Java $missingJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
            -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchToken $token
    }
    catch { $missingThrew = $true }
    Assert-Lease (-not $missingThrew -or $null -eq $missingExit) "a missing helper Java is reported rather than silently accepted"
    if (-not $missingThrew) {
        Assert-Lease ($missingExit -is [int]) "a missing helper Java still returns a single integer verdict"
        Assert-Lease ($missingExit -notin @(0, 10, 20, 30)) "a missing helper Java never inherits the previous allowed exit code"
        Assert-Lease ($missingExit -eq 127) "a missing helper Java fails closed with the explicit non-launchable code"
    }
    Assert-Lease (-not (Test-Path -LiteralPath $marker -PathType Leaf) -or ((Get-LeaseMarkerText $marker) -notmatch 'HELPER_ARGS=')) "the missing helper Java recorded no helper invocation"
    $global:LASTEXITCODE = 0

    # ------------------------------------------- pre-spawn exception releases, post-spawn retains
    Clear-LeaseMarker $marker
    $preSpawnThrew = $false
    try {
        Invoke-TurboismManagedLaunch -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
            -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchPrepared $true -LaunchToken $token `
            -LaunchAction { throw 'pre-spawn failure (no child started)' } | Out-Null
    }
    catch { $preSpawnThrew = $true }
    Assert-Lease $preSpawnThrew "pre-spawn failure propagates to the caller"
    $preSpawnMarker = Get-LeaseMarkerText $marker
    Assert-Lease ($preSpawnMarker -match '--release-launch') "pre-spawn failure releases this launch's LAUNCHING lease"
    Assert-Lease ($preSpawnMarker -notmatch '--prepare-launch') "the orchestration function itself never prepares"

    Clear-LeaseMarker $marker
    $postSpawnThrew = $false
    try {
        Invoke-TurboismManagedLaunch -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
            -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchPrepared $true -LaunchToken $token `
            -LaunchAction { $script:CubismManagedLaunchChildStarted = $true; throw 'post-spawn failure (child may be alive)' } | Out-Null
    }
    catch { $postSpawnThrew = $true }
    Assert-Lease $postSpawnThrew "post-spawn failure propagates to the caller"
    Assert-Lease ((Get-LeaseMarkerText $marker) -notmatch '--release-launch') "post-spawn failure retains the LAUNCHING lease"

    # The child-started flag is per launch: a retained lease from the previous call must not leak into
    # the next launch, which therefore releases again.
    Clear-LeaseMarker $marker
    $resetThrew = $false
    try {
        Invoke-TurboismManagedLaunch -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
            -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchPrepared $true -LaunchToken $token `
            -LaunchAction { throw 'second pre-spawn failure' } | Out-Null
    }
    catch { $resetThrew = $true }
    Assert-Lease $resetThrew "second pre-spawn failure propagates"
    Assert-Lease ((Get-LeaseMarkerText $marker) -match '--release-launch') "the child-started flag is reset for every launch"

    # Stray pipeline output from the launch action must not corrupt the exit verdict.
    Clear-LeaseMarker $marker
    $noisyExit = Invoke-TurboismManagedLaunch -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
        -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchPrepared $true -LaunchToken $token `
        -LaunchAction { 'noise on the success pipeline'; return 23 }
    Assert-Lease ($noisyExit -is [int]) "orchestration returns a single integer despite action output"
    Assert-Lease ($noisyExit -eq 23) "orchestration returns the launch action exit code"
    Assert-Lease ((Get-LeaseMarkerText $marker) -match '--release-launch') "normal synchronous exit releases the LAUNCHING lease"

    Clear-LeaseMarker $marker
    Invoke-TurboismManagedLaunch -Java $fakeJava -Agent $agent -TurboismHome $turboismHome -UpdateWork $updateWork `
        -UpdateJournal $updateJournal -UpdatePending $updatePending -LaunchPrepared $false -LaunchToken $token `
        -LaunchAction { return 0 } | Out-Null
    Assert-Lease ((Get-LeaseMarkerText $marker) -notmatch '--release-launch') "an unprepared launch never writes a lease or release"
    Remove-Item Env:TURBOISM_TEST_HELPER_EXIT -ErrorAction SilentlyContinue

    if (-not $SkipRealLauncher) {
        # ------------------------------------------------------- real launcher control flow
        Write-Host "ok: synthetic toolchain for the real launcher"
        $syntheticSource = @'
using System;
using System.IO;
using System.Text;
public static class SyntheticLeaseHost {
    // Exact argv record: UTF-16 code units of every argument as hex in an explicitly UTF-8 file.
    // No assertion depends on cmd's `echo` byte behaviour for the exact-value checks.
    static void RecordArgv(string[] args) {
        string target = Environment.GetEnvironmentVariable("TURBOISM_ARGV_FILE");
        if (String.IsNullOrEmpty(target)) return;
        StringBuilder block = new StringBuilder();
        block.Append("=== invocation ===" + Environment.NewLine);
        block.Append("argv=" + args.Length + Environment.NewLine);
        foreach (string value in args) {
            byte[] bytes = Encoding.Unicode.GetBytes(value ?? "");
            StringBuilder hex = new StringBuilder(bytes.Length * 2);
            foreach (byte b in bytes) hex.Append(b.ToString("x2"));
            block.Append(hex.ToString() + Environment.NewLine);
        }
        File.AppendAllText(target, block.ToString(), new UTF8Encoding(false));
    }
    public static int Main(string[] args) {
        string marker = Environment.GetEnvironmentVariable("TURBOISM_TEST_OUTPUT");
        if (Array.IndexOf(args, "dev.turboism.mapping.verification.CubismHostProbeCli") >= 0) {
            string result = args[args.Length - 1] + ".fixture-probe.json";
            if (!File.Exists(result)) return 2;
            Console.Write(File.ReadAllText(result));
            return 0;
        }
        if (Array.IndexOf(args, "dev.turboism.update.FrameworkUpdateHelper") >= 0) {
            // Mock launch-lease helper: echo arguments, print diagnostics, return a chosen exit code.
            if (String.IsNullOrEmpty(marker)) return 64;
            File.AppendAllText(marker, "HELPER_ARGS=" + String.Join(" ", args) + Environment.NewLine,
                System.Text.Encoding.Default);
            Console.WriteLine("NOTHING: synthetic helper diagnostic");
            Console.Error.WriteLine("NOTHING_ERR: synthetic helper diagnostic");
            RecordArgv(args);
            string exitEnv = Environment.GetEnvironmentVariable("TURBOISM_TEST_HELPER_EXIT");
            int exitCode = 10;
            if (!String.IsNullOrEmpty(exitEnv)) int.TryParse(exitEnv, out exitCode);
            return exitCode;
        }
        if (Array.IndexOf(args, "com.live2d.cubism.CECubismEditorApp") >= 0) {
            if (String.IsNullOrEmpty(marker)) return 64;
            File.AppendAllText(marker, "JAVA_ARGS=" + String.Join(" ", args) + Environment.NewLine,
                System.Text.Encoding.Default);
            RecordArgv(args);
            return 23;
        }
        return 64;
    }
}
'@
        $script:SyntheticProbe = Join-Path $temp "synthetic-lease-host.exe"
        Add-Type -Language CSharp -OutputType ConsoleApplication -OutputAssembly $script:SyntheticProbe -TypeDefinition $syntheticSource

        [System.IO.File]::WriteAllText(
            (Join-Path $turboismHome "config.json"),
            '{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-lease-fixture","launcher":{"cubismJvm":"bundled"}}',
            (New-Object System.Text.UTF8Encoding($false))
        )
        # Keep the fixture hermetic: no host GraalVM/Runtime Java may be adopted by the real launcher.
        Remove-Item Env:TURBOISM_CUBISM_JAVA -ErrorAction SilentlyContinue
        Remove-Item Env:TURBOISM_GRAALVM_HOME -ErrorAction SilentlyContinue
        Remove-Item Env:GRAALVM_HOME -ErrorAction SilentlyContinue
        $fixtureRoot = Join-Path $temp "fixtures"
        New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
        $goodRoot = New-LeaseCubismFixture -Root (Join-Path $fixtureRoot "Live2D Cubism 5.3.02") -Version "5.3.02"
        $brokenRoot = New-LeaseCubismFixture -Root (Join-Path $fixtureRoot "Live2D Cubism 5.2.03") -Version "5.2.03" -WithoutEditorMainClass $true

        # --- ProbeOnly never prepares, never stages a helper, never spawns ---
        Clear-LeaseMarker $marker
        $probeRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot, '-ProbeOnly')
        Assert-Lease ($probeRun.ExitCode -eq 0) "real launcher ProbeOnly succeeds (exit 0)"
        $probeMarkerText = Get-LeaseMarkerText $marker
        Assert-Lease ($probeMarkerText -notmatch 'HELPER_ARGS=') "real launcher ProbeOnly never prepares a launch lease"
        Assert-Lease ($probeMarkerText -notmatch 'JAVA_ARGS=') "real launcher ProbeOnly never starts the Cubism child"
        Assert-Lease (@(Get-ChildItem -LiteralPath $stageDir -File -Force -ErrorAction SilentlyContinue).Count -eq 0) "real launcher ProbeOnly leaves no staged helper/BAT files"

        # --- a failed prepare (helper verdict outside 0/10/20/30) must not spawn ---
        foreach ($failedVerdict in @(74, 64)) {
            Clear-LeaseMarker $marker
            $env:TURBOISM_TEST_HELPER_EXIT = [string]$failedVerdict
            $failedRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot)
            Assert-Lease ($failedRun.ExitCode -ne 0) "real launcher fails when the helper prepare exits $failedVerdict"
            $failedMarker = Get-LeaseMarkerText $marker
            Assert-Lease ($failedMarker -match '--prepare-launch') "the failing launch still attempted a real prepare (exit $failedVerdict)"
            Assert-Lease ($failedMarker -match [regex]::Escape('--launch-token ')) "the failed prepare carried a launch token (exit $failedVerdict)"
            Assert-Lease ($failedMarker -notmatch 'NORMAL=1') "no Cubism BAT ran after a failed prepare (exit $failedVerdict)"
            Assert-Lease ($failedMarker -notmatch 'JAVA_ARGS=') "no Cubism child was spawned after a failed prepare (exit $failedVerdict)"
            Assert-Lease (@(Get-ChildItem -LiteralPath $stageDir -Filter '.turboism-helper-*.jar' -ErrorAction SilentlyContinue).Count -eq 0) "a failed prepare leaves no temporary helper behind (exit $failedVerdict)"
        }
        Remove-Item Env:TURBOISM_TEST_HELPER_EXIT -ErrorAction SilentlyContinue

        # --- two ordinary no-pending launches each prepare, use their own token, and release ---
        $firstToken = $null
        foreach ($launchIndex in @(1, 2)) {
            Clear-LeaseMarker $marker
            $ordinaryRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot)
            $markerLines = @((Get-LeaseMarkerText $marker) -split "`r?`n" | Where-Object { $_ -ne '' })
            $prepareLines = @($markerLines | Where-Object { $_ -like 'HELPER_ARGS=*--prepare-launch *' })
            $releaseLines = @($markerLines | Where-Object { $_ -like 'HELPER_ARGS=*--release-launch *' })
            $javaLines = @($markerLines | Where-Object { $_ -like 'JAVA_ARGS=*' })
            Assert-Lease ($ordinaryRun.ExitCode -eq 23) "ordinary launch $launchIndex returns the Cubism child exit code"
            Assert-Lease ($prepareLines.Count -eq 1) "ordinary launch $launchIndex prepares exactly once without any pending update"
            Assert-Lease ($releaseLines.Count -eq 1) "ordinary launch $launchIndex releases its lease after a synchronous child exit"
            Assert-Lease ($javaLines.Count -eq 1) "ordinary launch $launchIndex spawns exactly one Cubism child"
            Assert-Lease (@($markerLines | Where-Object { $_ -eq 'NORMAL=1' }).Count -eq 1) "ordinary launch $launchIndex really executed the official BAT entry"
            $launchToken = $null
            if ($prepareLines[0] -match '--launch-token ([0-9a-f]{64})') { $launchToken = $Matches[1] }
            Assert-Lease ($null -ne $launchToken) "ordinary launch $launchIndex passes a 64-hex token to the helper"
            Assert-Lease ($releaseLines[0] -match [regex]::Escape('--launch-token ' + $launchToken)) "ordinary launch $launchIndex releases with the same token it prepared"
            Assert-Lease (([regex]::Matches($javaLines[0], [regex]::Escape('launchToken=' + $launchToken))).Count -eq 1) "ordinary launch $launchIndex carries that token into the javaagent exactly once"
            if ($launchIndex -eq 1) { $firstToken = $launchToken }
            else { Assert-Lease ($launchToken -ne $firstToken) "a second ordinary launch issues a distinct launch token" }
            Assert-Lease (@(Get-ChildItem -LiteralPath $stageDir -File -Force -ErrorAction SilentlyContinue).Count -eq 0) "ordinary launch $launchIndex cleans up its staged helper and BAT files"
        }

        # --- a pre-spawn production failure after prepare releases the LAUNCHING lease ---
        Clear-LeaseMarker $marker
        $brokenRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $brokenRoot)
        $brokenMarker = Get-LeaseMarkerText $marker
        Assert-Lease ($brokenRun.ExitCode -ne 0) "a pre-spawn launcher failure surfaces as a non-zero exit"
        Assert-Lease ($brokenMarker -match '--prepare-launch') "the pre-spawn failure happened after a real prepare"
        Assert-Lease ($brokenMarker -notmatch 'NORMAL=1' -and $brokenMarker -notmatch 'JAVA_ARGS=') "the pre-spawn failure started no Cubism process"
        Assert-Lease ($brokenMarker -match '--release-launch') "the pre-spawn failure released its own LAUNCHING lease"
        Assert-Lease (@(Get-ChildItem -LiteralPath $stageDir -File -Force -ErrorAction SilentlyContinue).Count -eq 0) "the pre-spawn failure left no staged launch artifacts"

        # --- a spawned child that exits non-zero still releases on the synchronous return ---
        Clear-LeaseMarker $marker
        $env:TURBOISM_TEST_CHILD_EXIT = "47"
        $childFailRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot)
        $childFailMarker = Get-LeaseMarkerText $marker
        Assert-Lease ($childFailRun.ExitCode -eq 47) "a failing Cubism child exit code is propagated by the real launcher"
        Assert-Lease ($childFailMarker -match 'JAVA_ARGS=') "the failing launch did start the Cubism child"
        Assert-Lease ($childFailMarker -match '--release-launch') "a synchronous child exit releases the lease even when the child failed"
        Assert-Lease ($childFailRun.Output -match 'CHILD_STDERR') "the child's stderr still reaches the launcher console without aborting the launch"
        Remove-Item Env:TURBOISM_TEST_CHILD_EXIT -ErrorAction SilentlyContinue

        # --- first-install receipt bootstrap wiring: only a non-Probe launch may initialize it ---
        $bootstrapMarker = Join-Path $temp "bootstrap-invoked.txt"
        $bootstrapDocs = Join-Path $turboismHome 'bootstrap'
        New-Item -ItemType Directory -Path $bootstrapDocs -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $bootstrapDocs 'manifest.json') -Value '{}' -Encoding ASCII
        Set-Content -LiteralPath (Join-Path $bootstrapDocs 'manifest.json.sig') -Value 'fixture' -Encoding ASCII
        Set-Content -LiteralPath (Join-Path $turboismHome 'bootstrap-receipt.ps1') -Encoding ASCII -Value @(
            'param($HomePath, $StagePath, $JavaPath, [switch]$PassThru)',
            ('Set-Content -LiteralPath "' + $bootstrapMarker + '" -Value $JavaPath'),
            '[pscustomobject]@{Success=$true; ExitCode=0}'
        )
        Clear-LeaseMarker $marker
        $probeWithDocs = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot, '-ProbeOnly')
        Assert-Lease ($probeWithDocs.ExitCode -eq 0) "ProbeOnly still succeeds when package bootstrap documents are present"
        Assert-Lease (-not (Test-Path -LiteralPath $bootstrapMarker -PathType Leaf)) "real launcher ProbeOnly never initializes the install receipt"
        Assert-Lease ((Get-LeaseMarkerText $marker) -notmatch 'HELPER_ARGS=') "ProbeOnly with bootstrap documents present still prepares no lease"

        Clear-LeaseMarker $marker
        $bootstrapRun = Invoke-RealLauncher @('-Home', $turboismHome, '-CubismRoot', $goodRoot)
        Assert-Lease ($bootstrapRun.ExitCode -eq 23) "a non-Probe launch still starts Cubism after the receipt bootstrap"
        Assert-Lease (Test-Path -LiteralPath $bootstrapMarker -PathType Leaf) "a non-Probe first launch invokes the package bootstrap documents"
        $bootstrapJava = if (Test-Path -LiteralPath $bootstrapMarker -PathType Leaf) { (Get-Content -LiteralPath $bootstrapMarker -Raw).Trim() } else { "" }
        Assert-Lease ($bootstrapJava -eq (Join-Path $goodRoot 'app\jre\bin\java.exe')) "receipt bootstrap receives the resolved update Java"

        # --- two-stage staged-BAT chain with Chinese + space everywhere ---
        # The launcher first writes a GraalVM Java-override intermediate BAT (active console code page)
        # and then feeds that intermediate into the managed-options staging, which used to read every
        # source as ANSI. The second read must use the code page the intermediate was written with.
        $chainParent = Join-Path $temp "chain 覆盖 空间"
        $chainRoot = Join-Path $chainParent "Live2D Cubism 覆盖 5.3.02"
        $overrideJava = Join-Path $chainParent "Graal 覆盖 空间\bin\java.exe"
        $chainHome = Join-Path $chainParent "Turboism 覆盖 空间"
        $chainMarker = Join-Path $chainParent "chain marker.txt"
        $argvEvidence = Join-Path $chainParent "chain argv.txt"
        New-Item -ItemType Directory -Path (Split-Path -Parent $overrideJava), $chainHome -Force | Out-Null
        New-LeaseCubismFixture -Root $chainRoot -Version "5.3.02" | Out-Null
        Copy-Item -LiteralPath $script:SyntheticProbe -Destination $overrideJava -Force
        [System.IO.File]::WriteAllBytes((Join-Path $chainHome "turboism-agent.jar"), [byte[]](9, 8, 7, 6))
        $chainOfficialBat = Join-Path $chainRoot "CubismEditor5.bat"
        $chainOfficialHash = Get-CubismSha256 $chainOfficialBat
        $savedArgvEnvironment = $env:TURBOISM_ARGV_FILE
        $savedMarkerEnvironment = $env:TURBOISM_TEST_OUTPUT
        $env:TURBOISM_ARGV_FILE = $argvEvidence
        $env:TURBOISM_TEST_OUTPUT = $chainMarker
        try {
            $chainRunners = @(
                [pscustomobject]@{ Name = "native console"; Invoke = { param($arguments) Invoke-RealLauncher $arguments } }
                [pscustomobject]@{ Name = "UTF-8 console"; Invoke = { param($arguments) Invoke-RealLauncherInUtf8Console $arguments } }
            )
            foreach ($chainRunner in $chainRunners) {
                Clear-LeaseMarker $argvEvidence
                Clear-LeaseMarker $chainMarker
                $chainRun = & $chainRunner.Invoke @('-Home', $chainHome, '-CubismRoot', $chainRoot, '-CubismJava', $overrideJava, 'chain probe')
                Assert-Lease ($chainRun.ExitCode -eq 23) ("the two-stage chain launches the override Java (" + $chainRunner.Name + ", exit " + $chainRun.ExitCode + "): " + ($chainRun.Output -replace "`r?`n", " | "))
                $chainMarkerText = Get-LeaseMarkerText $chainMarker
                Assert-Lease ($chainMarkerText -match 'HELPER_ARGS=.*--prepare-launch') ("the two-stage chain prepared a lease (" + $chainRunner.Name + ")")
                $chainTokenMatch = [regex]::Match($chainMarkerText, '--launch-token ([0-9a-f]{64})')
                Assert-Lease $chainTokenMatch.Success ("the two-stage chain carried a launch token (" + $chainRunner.Name + ")")
                $chainToken = $chainTokenMatch.Groups[1].Value
                $record = Get-LeaseArgvRecord -Path $argvEvidence -Containing 'com.live2d.cubism.CECubismEditorApp'
                $expectedArgv = @(
                    '-showversion',
                    "-Duser.dir=$chainRoot",
                    "-Dturboism.home=$chainHome",
                    "-javaagent:$(Join-Path $chainHome 'turboism-agent.jar')=home=$chainHome;timeoutSeconds=120;launchToken=$chainToken",
                    '-Dturboism.graal.enabled=false',
                    '-Djava.locale.providers=CLDR,SPI',
                    '-XX:+UseZGC',
                    '-XX:+DisableAttachMechanism',
                    'com.live2d.cubism.CECubismEditorApp',
                    (Join-Path $chainRoot 'chain probe')
                )
                $argvDiff = Compare-LeaseArgv -Actual $record.Values -Expected $expectedArgv
                Assert-Lease ($argvDiff -eq "") ("exact argv through the two-stage staged-BAT chain (" + $chainRunner.Name + "): " + $argvDiff)
                Assert-Lease ((Get-CubismSha256 $chainOfficialBat) -eq $chainOfficialHash) ("the official BAT stays byte-identical through the chain (" + $chainRunner.Name + ")")
                Assert-Lease (@(Get-ChildItem -LiteralPath (Join-Path $chainHome 'state\managed-launch') -File -Force -ErrorAction SilentlyContinue).Count -eq 0) ("the two-stage chain leaves no staged BAT or helper behind (" + $chainRunner.Name + ")")
            }
        }
        finally {
            $env:TURBOISM_ARGV_FILE = $savedArgvEnvironment
            $env:TURBOISM_TEST_OUTPUT = $savedMarkerEnvironment
        }
    }

    Write-Host "LAUNCH_LEASE_SYNTHETIC=PASS ($($script:LeaseAssertions) assertions)"
}
finally {
    foreach ($name in $savedEnvironment.Keys) {
        if ($null -eq $savedEnvironment[$name]) { Remove-Item ("Env:" + $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ("Env:" + $name) -Value $savedEnvironment[$name] }
    }
    # Only delete what this test created: the resolved path must still be this run's unique temp root,
    # directly under the process temp directory, and must not be a reparse point.
    $resolvedTemp = [System.IO.Path]::GetFullPath($temp)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $leaf = Split-Path -Leaf $resolvedTemp
    $isReparsePoint = $false
    if (Test-Path -LiteralPath $resolvedTemp) {
        $item = Get-Item -LiteralPath $resolvedTemp -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) { $isReparsePoint = (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) }
    }
    if (-not $isReparsePoint -and
        (Split-Path -Parent $resolvedTemp) -ieq $tempRoot -and
        $leaf -like 'turboism-lease-*' -and
        (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
