# Offline regression for the production native Graal installer.
# All files are synthetic; no Cubism, Java executable, or network is used.
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-graal-test-" + [guid]::NewGuid().ToString("N"))
[void][System.IO.Directory]::CreateDirectory($fixtureRoot)
$previousPath = $env:PATH
$previousJavaHome = $env:JAVA_HOME
$checks = 0

function Assert-InstallTest {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:checks++
    Write-Host "PASS: $Name"
}

function New-TestArchive {
    param([string]$Path, [string]$Version = "25.2.4", [string]$ExtraPath = "", [switch]$Duplicate)
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entries = @(
            @{ Name = "jdk/bin/java.exe"; Content = "deliberately not executable Java" },
            @{ Name = "jdk/release"; Content = "IMPLEMENTOR=`"GraalVM Community`"`nGRAALVM_VERSION=`"$Version`"`nJAVA_VERSION=`"25.0.4`"`n" },
            @{ Name = "jdk/lib/runtime.txt"; Content = "synthetic runtime payload" }
        )
        if ($ExtraPath) { $entries += @{ Name = $ExtraPath; Content = "must not escape" } }
        if ($Duplicate) { $entries += @{ Name = "JDK/BIN/JAVA.EXE"; Content = "duplicate must fail" } }
        foreach ($item in $entries) {
            $entry = $archive.CreateEntry($item.Name)
            $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
            try { $writer.Write($item.Content) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
    return @{
        Url = "https://github.com/offline-test/never-requested"
        Size = (Get-Item -LiteralPath $Path).Length
        Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
        GraalVersion = "25.2.4"; JavaVersion = "25.0.4"
    }
}

function Read-TestBytes {
    param([string]$Path)
    return [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Path))
}

function Assert-RejectedInstall {
    param([string]$Name, [string]$HomePath, [hashtable]$Manifest, [string]$ExpectedMessage)
    $beforeConfig = Read-TestBytes (Join-Path $HomePath "config.json")
    $beforeRuntime = Read-TestBytes (Join-Path $HomePath "graal/runtime/bin/java.exe")
    $failure = $null
    try { [void](Install-ManagedGraalRuntime $HomePath $Manifest) } catch { $failure = $_ }
    Assert-InstallTest ($null -ne $failure -and $failure.Exception.Message -match $ExpectedMessage) "$Name fails at the expected boundary"
    Assert-InstallTest ((Read-TestBytes (Join-Path $HomePath "config.json")) -ceq $beforeConfig) "$Name preserves config bytes"
    Assert-InstallTest ((Read-TestBytes (Join-Path $HomePath "graal/runtime/bin/java.exe")) -ceq $beforeRuntime) "$Name preserves previous runtime bytes"
}

try {
    # The entire production file is loaded, not a copied implementation.
    . (Join-Path $PSScriptRoot "install-managed-graal.ps1") -HomePath $fixtureRoot
    $productionReceiver = ${function:Receive-ManagedGraalArchive}
    $productionSave = ${function:Save-ManagedGraalConfig}
    $script:fixtureArchive = Join-Path $fixtureRoot "good.zip"
    $goodManifest = New-TestArchive $script:fixtureArchive
    $script:downloads = 0
    # Replace only the transport boundary, leaving all validation and publication real.
    function Receive-ManagedGraalArchive {
        param([hashtable]$Manifest, [string]$Destination)
        $script:downloads++
        [System.IO.File]::Copy($script:fixtureArchive, $Destination)
    }
    $env:PATH = ""
    $env:JAVA_HOME = ""
    $installHome = Join-Path $fixtureRoot "Turboism space 路径"
    [void][System.IO.Directory]::CreateDirectory($installHome)
    $configPath = Join-Path $installHome "config.json"
    [System.IO.File]::WriteAllText($configPath, '{"schemaVersion":1,"launcher":{"cubismJvm":"bundled","graalVmPath":"old-path"},"plugins":{"disabledPlugins":["example.plugin"]},"logLevel":"DEBUG"}')
    $javaPath = Install-ManagedGraalRuntime $installHome $goodManifest
    Assert-InstallTest (([System.IO.File]::ReadAllText($javaPath)) -ceq "deliberately not executable Java") "installs without any runnable Java or agent JAR"
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallTest ($config.launcher.graalVmPath -ceq (Join-Path $installHome "graal/runtime")) "writes the managed runtime directory to configuration"
    Assert-InstallTest ($config.launcher.cubismJvm -ceq "bundled") "preserves explicit bundled JVM preference"
    Assert-InstallTest ($config.plugins.disabledPlugins[0] -ceq "example.plugin" -and $config.logLevel -ceq "DEBUG") "preserves unrelated user configuration"
    Assert-InstallTest ((Find-CubismGraalJava -TurboismHome $installHome) -ceq $javaPath) "normal launcher discovers the installed runtime without execution"
    [void](Install-ManagedGraalRuntime $installHome $goodManifest)
    Assert-InstallTest ((Get-ChildItem -LiteralPath (Join-Path $installHome "graal") -Filter ".install-*" -Force).Count -eq 0) "successful reinstall removes private staging"

    $emptyHome = Join-Path $fixtureRoot "fresh"
    [void][System.IO.Directory]::CreateDirectory($emptyHome)
    [void](Install-ManagedGraalRuntime $emptyHome $goodManifest)
    $fresh = Get-Content -LiteralPath (Join-Path $emptyHome "config.json") -Raw | ConvertFrom-Json
    Assert-InstallTest ($fresh.schemaVersion -eq 1 -and $fresh.launcher.graalVmPath -eq (Join-Path $emptyHome "graal/runtime")) "fresh installation creates compatible path configuration"

    $badHash = $goodManifest.Clone(); $badHash.Sha256 = "0" * 64
    Assert-RejectedInstall "incorrect digest" $installHome $badHash "SHA-256"
    $badSize = $goodManifest.Clone(); $badSize.Size++
    Assert-RejectedInstall "incorrect archive size" $installHome $badSize "size"
    $script:fixtureArchive = Join-Path $fixtureRoot "wrong-release.zip"
    $badRelease = New-TestArchive $script:fixtureArchive -Version "0.0.0"
    Assert-RejectedInstall "wrong release metadata" $installHome $badRelease "metadata"
    $script:fixtureArchive = Join-Path $fixtureRoot "traversal.zip"
    $traversal = New-TestArchive $script:fixtureArchive -ExtraPath "jdk/../../outside.txt"
    Assert-RejectedInstall "ZIP traversal" $installHome $traversal "Unsafe"
    Assert-InstallTest (-not (Test-Path -LiteralPath (Join-Path $installHome "graal/outside.txt"))) "ZIP traversal writes no outside file"
    $script:fixtureArchive = Join-Path $fixtureRoot "duplicate.zip"
    $duplicate = New-TestArchive $script:fixtureArchive -Duplicate
    Assert-RejectedInstall "case-insensitive duplicate ZIP path" $installHome $duplicate "Duplicate"
    $script:fixtureArchive = Join-Path $fixtureRoot "good.zip"

    function Save-ManagedGraalConfig { throw "synthetic configuration write failure" }
    Assert-RejectedInstall "config write failure" $installHome $goodManifest "synthetic configuration write failure"
    Set-Item -Path Function:Save-ManagedGraalConfig -Value $productionSave

    $script:graalCancelled = $true
    Assert-RejectedInstall "cancellation" $installHome $goodManifest "cancelled"
    $script:graalCancelled = $false

    $invalidHome = Join-Path $fixtureRoot "invalid-config"
    [void][System.IO.Directory]::CreateDirectory($invalidHome)
    foreach ($invalidJson in @('{broken', '{"schemaVersion":2}', '{"schemaVersion":"1"}', '{"schemaVersion":1.0}', '{"schemaVersion":1,"launcher":[]}', (' ' * 65537))) {
        $invalidConfig = Join-Path $invalidHome "config.json"
        [System.IO.File]::WriteAllText($invalidConfig, $invalidJson)
        $before = Read-TestBytes $invalidConfig
        $beforeDownloads = $script:downloads
        $failure = $null
        try { [void](Install-ManagedGraalRuntime $invalidHome $goodManifest) } catch { $failure = $_ }
        Assert-InstallTest ($null -ne $failure -and $script:downloads -eq $beforeDownloads) "invalid config is rejected before downloading"
        Assert-InstallTest ((Read-TestBytes $invalidConfig) -ceq $before) "invalid config remains byte-for-byte unchanged"
    }

    $beforeRuntime = Read-TestBytes $javaPath
    function Receive-ManagedGraalArchive {
        param([hashtable]$Manifest, [string]$Destination)
        [System.IO.File]::Copy($script:fixtureArchive, $Destination)
        [System.IO.File]::WriteAllText($configPath, '{"schemaVersion":1,"logLevel":"WARN"}')
    }
    $failure = $null
    try { [void](Install-ManagedGraalRuntime $installHome $goodManifest) } catch { $failure = $_ }
    Assert-InstallTest ($null -ne $failure -and $failure.Exception.Message -match "changed during") "concurrent config edit prevents publication"
    Assert-InstallTest (([System.IO.File]::ReadAllText($configPath)) -ceq '{"schemaVersion":1,"logLevel":"WARN"}') "concurrent user edit is not overwritten"
    Assert-InstallTest ((Read-TestBytes $javaPath) -ceq $beforeRuntime) "concurrent config edit restores previous runtime"

    Write-Host "Native managed Graal installer: $checks checks passed. No Java or network required."
} finally {
    $env:PATH = $previousPath
    $env:JAVA_HOME = $previousJavaHome
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}
