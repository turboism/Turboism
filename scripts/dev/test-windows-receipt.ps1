[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Bundle,
    [string]$FixtureRoot = (Join-Path $env:LOCALAPPDATA 'Turboism-W2-Fixture'),
    [string]$ExpectedBundleSha256,
    [switch]$BuildNative,
    [string]$Compiler = 'gcc',
    [string]$Objdump = 'objdump',
    [string]$Python = 'python',
    [switch]$PrepareBundle
)
$ErrorActionPreference = 'Stop'
# Only spawned fixture processes may be killed on timeout; never touch Cubism/editors.
function Invoke-BoundedFixture([string]$Executable, [string[]]$Arguments, [string]$Name) {
    $out = Join-Path $reportDirectory ($Name + '.stdout')
    $err = Join-Path $reportDirectory ($Name + '.stderr')
    $quoted = @($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' })
    $start = @{ FilePath=$Executable; PassThru=$true; NoNewWindow=$true; RedirectStandardOutput=$out; RedirectStandardError=$err }
    if ($quoted.Count -gt 0) { $start.ArgumentList = $quoted }
    $process = Start-Process @start
    try {
        $null = $process.Handle
        if (!$process.WaitForExit(120000)) {
            $process.Kill()
            $process.WaitForExit(5000) | Out-Null
            throw "$Name fixture exceeded 120 seconds; only its test process was terminated"
        }
        if ($null -eq $process.ExitCode) { throw "$Name fixture exit status unavailable" }
        return $process.ExitCode
    } finally { $process.Dispose() }
}
function Read-BundleText($Stream) {
    $Stream.Position = 0
    $reader = [IO.StreamReader]::new($Stream, [Text.UTF8Encoding]::new($false, $true), $false, 4096, $true)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose(); $Stream.Position = 0 }
}
function Get-StreamDigest($Stream) {
    $Stream.Position = 0
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Stream))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose(); $Stream.Position = 0 }
}
function Open-BundleFile([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Bundle link/directory rejected' }
    # Retain read-only handles through fixture completion. This is still an operator-owned
    # test handoff, not the W4 production DLL loading/ancestor race security boundary.
    $stream = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $bundleStreams.Add($stream)
    return $stream
}
function Verify-Bundle([string]$Directory, [string]$Expected) {
    if ($Expected -cnotmatch '^[a-f0-9]{64}$') { throw 'Independent -ExpectedBundleSha256 is required for the whole bundle manifest' }
    $ancestor = Get-Item -LiteralPath $Directory -Force
    while ($null -ne $ancestor) {
        if (!($ancestor.Attributes -band [IO.FileAttributes]::Directory) -or ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Bundle ancestor link rejected' }
        $ancestor = $ancestor.Parent
    }
    $manifest = Open-BundleFile (Join-Path $Directory 'bundle-manifest.json')
    if ($manifest.Length -gt 16384 -or (Get-StreamDigest $manifest) -cne $Expected) { throw 'Bundle manifest digest mismatch/limit' }
    $payload = Read-BundleText $manifest | ConvertFrom-Json
    if (@($payload.PSObject.Properties).Count -ne 3 -or $payload.format -cne 'turboism-w2-validation-bundle-v1' -or
        !($payload.schemaVersion -is [int] -or $payload.schemaVersion -is [long]) -or $payload.schemaVersion -ne 1 -or @($payload.files).Count -ne 7) { throw 'Invalid bundle manifest schema' }
    $records = @{}
    [long]$total = 0
    foreach ($record in $payload.files) {
        if (@($record.PSObject.Properties).Count -ne 3 -or !($record.path -is [string]) -or $record.path -cnotmatch '^[a-z0-9][a-z0-9._-]*$' -or $record.path.Contains('..') -or $records.ContainsKey($record.path) -or
            !($record.bytes -is [int] -or $record.bytes -is [long]) -or $record.bytes -le 0 -or $record.bytes -gt 67108864 -or !($record.sha256 -is [string]) -or $record.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid bundle file record' }
        $total += $record.bytes
        $records[$record.path] = $record
    }
    if ($total -gt 134217728) { throw 'Bundle total size exceeded' }
    $required = @('native-build.json', 'turboism-update-files.dll', 'windows-fixture-probe.exe', 'windows-receipt-fixture.jar')
    foreach ($name in $required) { if (!$records.ContainsKey($name)) { throw "Missing bundle file: $name" } }
    $jars = @($records.Keys | Where-Object { $required -cnotcontains $_ })
    foreach ($component in @('annotations', 'core', 'databind')) {
        if (@($jars | Where-Object { $_ -cmatch ('^jackson-' + $component + '-[0-9][A-Za-z0-9._-]*\.jar$') }).Count -ne 1) { throw 'Bundle requires exactly three Jackson components' }
    }
    $disk = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($disk.Count -ne 8) { throw 'Unexpected bundle file/directory inventory' }
    foreach ($item in $disk) {
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or ($item.Name -cne 'bundle-manifest.json' -and !($records.Keys -ccontains $item.Name))) { throw 'Extra executable/classpath file or unsafe bundle entry' }
    }
    $streams = @{}
    foreach ($name in $records.Keys) {
        $stream = Open-BundleFile (Join-Path $Directory $name)
        if ($stream.Length -ne $records[$name].bytes -or (Get-StreamDigest $stream) -cne $records[$name].sha256) { throw "Bundle content mismatch: $name" }
        $streams[$name] = $stream
    }
    return @{ records=$records; streams=$streams }
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$Bundle) { $Bundle = Join-Path $repo 'build/native/windows-receipt-validation' }
$Bundle = [IO.Path]::GetFullPath($Bundle)
$reportDirectory = Join-Path $repo ('build/native/windows-receipt-report/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$reportPath = Join-Path $reportDirectory 'report.json'
$report = [ordered]@{ schemaVersion=1; productionEnabled=$false; status='NOT_RUN'; sourceRevision=$null; cases=@(); limitations=@('No production DLL loading trust chain','ADS including MOTW rejected','W3 cache and Helper not implemented') }
$exit = 1
$bundleStreams = [Collections.Generic.List[IO.FileStream]]::new()
try {
    # Reject unsupported combinations before any compiler/Gradle/JDK invocation.
    $defaultBundle = [IO.Path]::GetFullPath((Join-Path $repo 'build/native/windows-receipt-validation'))
    if ($BuildNative -ne $PrepareBundle) { throw 'Use -BuildNative and -PrepareBundle together, or use an independently authenticated prebuilt bundle' }
    if ($PrepareBundle -and ![String]::Equals($Bundle, $defaultBundle, [StringComparison]::OrdinalIgnoreCase)) { throw 'Local preparation supports only the default bundle directory; omit -Bundle' }
    if ($env:OS -ne 'Windows_NT' -or [IntPtr]::Size -ne 8) { throw 'Native Windows x64 required' }
    $java = Join-Path $JdkHome 'bin/java.exe'
    $javac = Join-Path $JdkHome 'bin/javac.exe'
    if (!(Test-Path $java) -or !(Test-Path $javac)) { throw 'Provide -JdkHome for a local Windows JDK17' }
    $report.os = [Environment]::OSVersion.VersionString
    $report.sourceRevision = (& git -C $repo rev-parse HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checkout SHA' }
    $report.dirty = [bool](& git -C $repo status --porcelain)
    if ($BuildNative) {
        if (!(Get-Command $Compiler -ErrorAction SilentlyContinue)) { throw 'MinGW-w64 x64 GCC missing. No tools installed. Use an explicitly delivered cross-built bundle + SHA256 instead.' }
        if (!(Get-Command $Objdump -ErrorAction SilentlyContinue)) { throw 'MinGW objdump missing; import gate cannot be skipped' }
        if (!(Get-Command $Python -ErrorAction SilentlyContinue)) { throw 'Python3.11+ required for recorded native build gate' }
        & $Python (Join-Path $repo 'scripts/dev/build_windows_update_files.py') --cc $Compiler --objdump $Objdump --jdk $JdkHome --output $Bundle
        if ($LASTEXITCODE -ne 0) { throw 'Native build/pin/import/reproducibility gate failed' }
    }
    if ($PrepareBundle) {
        Push-Location $repo
        try { & (Join-Path $repo 'gradlew.bat') ':runtime:windowsReceiptValidationBundle' '--console=plain'; if ($LASTEXITCODE -ne 0) { throw 'Fixture Java bundle build failed; Gradle/JDK/Python project prerequisites required' } }
        finally { Pop-Location }
    }
    if ($BuildNative) {
        $sealText = & $Python (Join-Path $repo 'scripts/dev/build_windows_update_files.py') --seal-bundle --output $Bundle
        if ($LASTEXITCODE -ne 0) { throw 'Full bundle sealing failed' }
        $localDigest = ($sealText | Out-String | ConvertFrom-Json).bundleManifestSha256
        if ($ExpectedBundleSha256 -and $ExpectedBundleSha256 -cne $localDigest) { throw 'Locally built bundle differs from supplied independent digest' }
        $ExpectedBundleSha256 = $localDigest
    }
    # No bundled Java or probe may execute until the entire exact inventory is authenticated.
    $verified = Verify-Bundle $Bundle $ExpectedBundleSha256
    $jdkVersion = (& $javac -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $jdkVersion -notmatch '^javac 17(?:\.|$)') { throw 'JDK17 required' }
    $report.jdk = $jdkVersion
    $report.bundleManifestSha256 = $ExpectedBundleSha256
    $build = (Read-BundleText ($verified.streams['native-build.json'])) | ConvertFrom-Json
    $dll = Join-Path $Bundle 'turboism-update-files.dll'
    $ExpectedDllSha256 = $verified.records['turboism-update-files.dll'].sha256
    if ($build.dllSha256 -cne $ExpectedDllSha256 -or $build.probeSha256 -cne $verified.records['windows-fixture-probe.exe'].sha256) { throw 'Native report differs from authenticated inventory' }
    foreach ($item in $build.sources.PSObject.Properties) {
        if ($item.Name -notmatch '^native/update-files/[A-Za-z0-9/_.-]+$' -or $item.Name.Contains('..')) { throw 'Invalid build source record' }
        if ((Get-FileHash -Algorithm SHA256 (Join-Path $repo $item.Name)).Hash.ToLowerInvariant() -ne $item.Value) { throw "Build source mismatch: $($item.Name)" }
    }
    $report.nativeBuild = $build
    $report.binaries = @($verified.records.Values)
    $FixtureRoot = [IO.Path]::GetFullPath($FixtureRoot)
    if ([IO.Path]::GetFileName($FixtureRoot) -notlike 'Turboism-W2-Fixture*' -or (Test-Path $FixtureRoot)) { throw 'FixtureRoot must be a NEW Turboism-W2-Fixture* directory. Existing directories are never deleted/reused.' }
    New-Item -ItemType Directory -Path $FixtureRoot | Out-Null
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($user)
    foreach ($sid in @($user, ([Security.Principal.SecurityIdentifier]::new('S-1-5-18')), ([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit', [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $FixtureRoot -AclObject $acl
    # Only this fresh fixture receives a private ACL. No user installation/global ACL changes.
    $javaRoot = Join-Path $FixtureRoot 'java'
    New-Item -ItemType Directory -Path $javaRoot | Out-Null
    $javaReport = Join-Path $reportDirectory 'java.json'
    $classpath = (@($verified.records.Keys | Where-Object { $_.EndsWith('.jar') } | Sort-Object | ForEach-Object { Join-Path $Bundle $_ }) -join ';')
    $javaExit = Invoke-BoundedFixture $java @('-Djava.awt.headless=true', '-cp', $classpath, 'dev.turboism.update.WindowsReceiptNativeFixture', $javaRoot, $dll, $ExpectedDllSha256, $javaReport) 'java'
    if (!(Test-Path $javaReport)) { throw 'Java fixture failed before report; inspect console. Native acceptance NOT verified.' }
    $report.java = Get-Content -Raw $javaReport | ConvertFrom-Json
    $report.cases += $report.java.cases
    $probe = Join-Path $Bundle 'windows-fixture-probe.exe'
    $env:TURBOISM_W2_NATIVE_FIXTURE = Join-Path $FixtureRoot 'native-probe'
    try {
        $probeExit = Invoke-BoundedFixture $probe @() 'native-probe'
        $probeLines = @(Get-Content (Join-Path $reportDirectory 'native-probe.stdout'))
    }
    finally { Remove-Item Env:TURBOISM_W2_NATIVE_FIXTURE -ErrorAction SilentlyContinue }
    foreach ($line in $probeLines) { $report.cases += ($line | ConvertFrom-Json) }
    $unverified = @($report.cases | Where-Object { $_.status -ne 'PASS' })
    $report.status = if ($javaExit -eq 0 -and $probeExit -eq 0 -and $unverified.Count -eq 0) { 'PASS' } else { 'INCOMPLETE_OR_FAILED' }
    $report.fixtureRoot = $FixtureRoot
    if ($report.status -eq 'PASS') { $exit = 0 } else { $exit = 2 }
} catch {
    $report.status = 'FAILED_OR_NOT_RUN'
    $report.error = $_.Exception.Message
} finally {
    foreach ($stream in $bundleStreams) { $stream.Dispose() }
    $report | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $reportPath
    Write-Host "W2 report: $reportPath ($($report.status)). Fixtures retained; no installation touched."
}
exit $exit
