# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [Parameter(Mandatory = $true)]
    [string]$HomePath,
    # Retained for callers of older installers; provisioning does not use it.
    [string]$Java = "",
    [switch]$Gui
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")
$script:graalUi = $null
$script:graalCancelled = $false
$script:graalLogPath = ""

function Get-ManagedGraalManifest {
    # Keep these pins identical to ManagedGraalRuntimeService.Platform.WINDOWS_X64.
    return @{
        Url = "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/graalvm-community-jdk-25i2-25.0.4_windows-x64_bin.zip"
        Size = 341299924L
        Sha256 = "789d2af1c06c3c24f402d2d4a711bdbb19b36f7d8c74afe6a959492fd121ef33"
        GraalVersion = "25.2.4"
        JavaVersion = "25.0.4"
    }
}

function Write-ManagedGraalLog {
    param([string]$Message, [string]$LogPath = $script:graalLogPath)
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        try { Add-Content -LiteralPath $LogPath -Value $Message -Encoding UTF8 }
        catch { [Console]::Error.WriteLine("GraalVM installer log could not be written: " + $_.Exception.Message) }
    }
}

function Test-ManagedGraalCancellation {
    if ($null -ne $script:graalUi) { [System.Windows.Forms.Application]::DoEvents() }
    if ($script:graalCancelled) { throw [System.OperationCanceledException]::new("GraalVM installation was cancelled.") }
}

function Write-ManagedGraalProgress {
    param([string]$State, [long]$Done = 0, [long]$Total = 0)
    $line = "GRAAL_RUNTIME_PROGRESS $State $Done/$Total"
    [Console]::Out.WriteLine($line)
    Write-ManagedGraalLog $line
    if ($null -ne $script:graalUi) {
        $ui = $script:graalUi
        $ui.Status.Text = $ui.Strings[$State]
        if ($State -eq "DOWNLOADING" -and $Total -gt 0) {
            $ui.Progress.Style = "Continuous"
            $ui.Progress.Value = [Math]::Min(1000, [int](1000.0 * $Done / $Total))
            $seconds = [Math]::Max(0.001, ([DateTime]::UtcNow - $ui.LastTime).TotalSeconds)
            $rate = [Math]::Max(0, ($Done - $ui.LastBytes) / $seconds)
            $ui.Detail.Text = "{0:N1} / {1:N1} MiB   {2:N1} MiB/s" -f ($Done / 1MB), ($Total / 1MB), ($rate / 1MB)
            $ui.LastTime = [DateTime]::UtcNow; $ui.LastBytes = $Done
        } else { $ui.Progress.Style = "Marquee"; $ui.Detail.Text = "" }
        [System.Windows.Forms.Application]::DoEvents()
    }
}

function Assert-ManagedGraalPath {
    param([string]$Root, [string]$Path)
    $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $candidate = [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $prefix = $rootPath + [System.IO.Path]::DirectorySeparatorChar
    if ($candidate -ine $rootPath -and -not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Managed Graal path escapes Turboism home: $Path"
    }
    while ($candidate.Length -ge $rootPath.Length) {
        $item = Get-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
        if ($null -ne $item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Managed Graal path contains a reparse point: $candidate"
        }
        if ($candidate -ieq $rootPath) { break }
        $candidate = [System.IO.Path]::GetDirectoryName($candidate)
    }
}

function Wait-ManagedGraalTask {
    param([System.Threading.Tasks.Task]$Task)
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not $Task.IsCompleted) {
        Test-ManagedGraalCancellation
        if ([DateTime]::UtcNow -gt $deadline) { throw "GraalVM download timed out waiting for network data." }
        Start-Sleep -Milliseconds 50
    }
    Test-ManagedGraalCancellation
    return $Task.GetAwaiter().GetResult()
}

function Receive-ManagedGraalArchive {
    param([hashtable]$Manifest, [string]$Destination)
    Add-Type -AssemblyName System.Net.Http
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor [System.Net.SecurityProtocolType]::Tls12
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromHours(4)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("Turboism-Installer")
    $response = $null; $inputStream = $null; $outputStream = $null
    $cancellation = [System.Threading.CancellationTokenSource]::new()
    try {
        $uri = [uri]$Manifest.Url
        for ($redirect = 0; ; $redirect++) {
            if ($uri.Scheme -cne "https" -or $uri.Port -ne 443 -or $uri.UserInfo -or
                @("github.com", "release-assets.githubusercontent.com") -notcontains $uri.DnsSafeHost) {
                throw "GraalVM download redirected outside the official HTTPS release hosts."
            }
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $uri)
            try {
                $response = Wait-ManagedGraalTask ($client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancellation.Token))
            } finally { $request.Dispose() }
            $status = [int]$response.StatusCode
            if (@(301, 302, 303, 307, 308) -notcontains $status) { break }
            if ($redirect -ge 5 -or $null -eq $response.Headers.Location) { throw "Too many or invalid GraalVM download redirects." }
            $uri = [uri]::new($uri, $response.Headers.Location)
            $response.Dispose(); $response = $null
        }
        if ($status -ne 200) { throw "GraalVM download returned HTTP $status." }
        $length = $response.Content.Headers.ContentLength
        if ($null -ne $length -and $length -ne $Manifest.Size) { throw "GraalVM archive size does not match the pinned release." }
        $inputStream = Wait-ManagedGraalTask ($response.Content.ReadAsStreamAsync())
        $outputStream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $buffer = New-Object byte[] 65536
        $done = 0L; $lastUpdate = [DateTime]::UtcNow; $deadline = $lastUpdate.AddHours(4)
        Write-ManagedGraalProgress "DOWNLOADING" 0 $Manifest.Size
        while ($true) {
            if ([DateTime]::UtcNow -gt $deadline) { throw "GraalVM download exceeded its time limit." }
            $count = Wait-ManagedGraalTask ($inputStream.ReadAsync($buffer, 0, $buffer.Length, $cancellation.Token))
            if ($count -eq 0) { break }
            $done += $count
            if ($done -gt $Manifest.Size -or $done -gt 400MB) { throw "GraalVM archive exceeds its pinned size." }
            $outputStream.Write($buffer, 0, $count)
            if (([DateTime]::UtcNow - $lastUpdate).TotalMilliseconds -ge 200) {
                Write-ManagedGraalProgress "DOWNLOADING" $done $Manifest.Size
                $lastUpdate = [DateTime]::UtcNow
            }
        }
        Write-ManagedGraalProgress "DOWNLOADING" $done $Manifest.Size
    } finally {
        $cancellation.Cancel()
        if ($null -ne $outputStream) { $outputStream.Dispose() }
        if ($null -ne $inputStream) { $inputStream.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $client.Dispose(); $cancellation.Dispose()
    }
}

function Expand-ManagedGraalArchive {
    param([string]$ArchivePath, [string]$Destination)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        if ($zip.Entries.Count -eq 0 -or $zip.Entries.Count -gt 32000) { throw "Invalid GraalVM archive entry count." }
        $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $expanded = 0L
        foreach ($entry in $zip.Entries) {
            Test-ManagedGraalCancellation
            $name = $entry.FullName.Replace('\', '/')
            $parts = $name.TrimEnd('/').Split('/')
            if ([string]::IsNullOrWhiteSpace($name) -or $name.StartsWith('/') -or
                $name -match '[\x00-\x1F<>:"|?*]' -or
                (($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960 -or
                ($entry.ExternalAttributes -band 1024) -ne 0) { throw "Unsafe GraalVM archive entry: $name" }
            foreach ($part in $parts) {
                if ([string]::IsNullOrWhiteSpace($part) -or $part -eq '.' -or $part -eq '..' -or
                    $part -match '[. ]$' -or $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {
                    throw "Unsafe GraalVM archive path: $name"
                }
            }
            $target = Join-Path $Destination ($parts -join [System.IO.Path]::DirectorySeparatorChar)
            Assert-ManagedGraalPath $Destination $target
            if (-not $seen.Add($target)) { throw "Duplicate GraalVM archive path: $name" }
            $expanded += $entry.Length
            if ($expanded -gt 2GB) { throw "GraalVM extracted files exceed the size limit." }
            if ($name.EndsWith('/')) { [void][System.IO.Directory]::CreateDirectory($target); continue }
            [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($target))
            $inputStream = $entry.Open()
            $outputStream = $null
            try {
                $outputStream = [System.IO.File]::Open($target, [System.IO.FileMode]::CreateNew)
                $buffer = New-Object byte[] 65536; $written = 0L
                while (($count = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    Test-ManagedGraalCancellation
                    $written += $count
                    if ($written -gt $entry.Length) { throw "GraalVM archive entry exceeds its declared size." }
                    $outputStream.Write($buffer, 0, $count)
                }
                if ($written -ne $entry.Length) { throw "Truncated GraalVM archive entry: $name" }
            } finally { $inputStream.Dispose(); if ($null -ne $outputStream) { $outputStream.Dispose() } }
        }
    } finally { $zip.Dispose() }
    $roots = @(Get-ChildItem -LiteralPath $Destination -Force)
    if ($roots.Count -ne 1 -or -not $roots[0].PSIsContainer) { throw "GraalVM archive must contain one runtime directory." }
    return $roots[0].FullName
}

function Assert-ManagedGraalRelease {
    param([string]$RuntimePath, [hashtable]$Manifest)
    foreach ($relative in @("bin/java.exe", "release")) {
        $path = Join-Path $RuntimePath $relative
        Assert-ManagedGraalPath $RuntimePath $path
        if (-not (Test-CubismNormalFile $path) -or (Get-Item -LiteralPath $path).Length -eq 0) { throw "GraalVM runtime file is missing: $relative" }
    }
    $releasePath = Join-Path $RuntimePath "release"
    if ((Get-Item -LiteralPath $releasePath).Length -gt 65536) { throw "GraalVM release metadata exceeds the size limit." }
    $metadata = [System.IO.File]::ReadAllText($releasePath)
    $expected = @{ IMPLEMENTOR = "GraalVM Community"; GRAALVM_VERSION = $Manifest.GraalVersion; JAVA_VERSION = $Manifest.JavaVersion }
    foreach ($key in $expected.Keys) {
        $pattern = '(?m)^' + [regex]::Escape($key) + '="' + [regex]::Escape($expected[$key]) + '"\r?$'
        if (-not [regex]::IsMatch($metadata, $pattern)) { throw "GraalVM release metadata does not match the pinned $key." }
    }
}

function New-ManagedGraalConfig {
    param([string]$TurboismHome, [string]$RuntimePath)
    $path = Join-Path $TurboismHome "config.json"
    Assert-ManagedGraalPath $TurboismHome $path
    $original = $null
    if (Test-Path -LiteralPath $path) {
        if (-not (Test-CubismNormalFile $path)) { throw "Turboism config is not a normal file." }
        $text = Read-CubismStateBytes $path
        # Strict UTF-8 decoding in the bounded reader makes this an exact byte snapshot.
        $original = [System.Text.Encoding]::UTF8.GetBytes($text)
        if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
        $document = ConvertFrom-Json -InputObject $text -ErrorAction Stop
        if ($document -isnot [pscustomobject]) { throw "Turboism config must be an object." }
        $schema = $document.PSObject.Properties["schemaVersion"]
        if ($null -eq $schema -or ($schema.Value -isnot [int] -and $schema.Value -isnot [long]) -or $schema.Value -ne 1) {
            throw "Turboism config must use integer schemaVersion 1. Run the main installer to migrate it first."
        }
    } else {
        $document = [pscustomobject]@{
            format = "turboism.runtime.config"
            schemaVersion = 1
            worktreeId = "turboism-runtime"
            pluginDirs = @("plugins")
            useTextIcon = $false
            launcher = [pscustomobject]@{ cubismJvm = "graalvm" }
        }
    }
    $launcher = $document.PSObject.Properties["launcher"]
    if ($null -eq $launcher) {
        $document | Add-Member -NotePropertyName launcher -NotePropertyValue ([pscustomobject]@{})
    } elseif ($launcher.Value -isnot [pscustomobject]) { throw "Turboism launcher config must be an object." }
    # Installing a runtime must not override an explicit bundled-JVM preference.
    $document.launcher | Add-Member -NotePropertyName graalVmPath -NotePropertyValue $RuntimePath -Force
    $json = ConvertTo-Json -InputObject $document -Depth 100
    if ([System.Text.Encoding]::UTF8.GetByteCount($json) -gt 65536) { throw "Turboism config exceeds the size limit." }
    return @{ Path = $path; Original = $original; Json = $json }
}

function Save-ManagedGraalConfig {
    param([string]$TurboismHome, [hashtable]$Plan, [string]$Staging)
    Assert-ManagedGraalPath $TurboismHome $Plan.Path
    if ($null -ne $Plan.Original) {
        if (-not (Test-CubismNormalFile $Plan.Path) -or
            [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes((Read-CubismStateBytes $Plan.Path))) -cne [Convert]::ToBase64String($Plan.Original)) {
            throw "Turboism config changed during GraalVM installation; retry without editing it."
        }
    } elseif (Test-Path -LiteralPath $Plan.Path) { throw "Turboism config was created during GraalVM installation; retry." }
    $temporary = Join-Path $Staging "config.json"
    Assert-ManagedGraalPath $TurboismHome $temporary
    [System.IO.File]::WriteAllText($temporary, $Plan.Json, [System.Text.UTF8Encoding]::new($false))
    if ($null -ne $Plan.Original) { [System.IO.File]::Replace($temporary, $Plan.Path, (Join-Path $Staging "previous-config.json")) }
    else { [System.IO.File]::Move($temporary, $Plan.Path) }
}

function Remove-ManagedGraalStaging {
    param([string]$TurboismHome, [string]$Path)
    Assert-ManagedGraalPath $TurboismHome $Path
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force) {
        Assert-ManagedGraalPath $TurboismHome $item.FullName
        if ($item.PSIsContainer) { Remove-ManagedGraalStaging $TurboismHome $item.FullName }
        else { [System.IO.File]::Delete($item.FullName) }
    }
    [System.IO.Directory]::Delete($Path)
}

function Install-ManagedGraalRuntime {
    param([string]$TurboismHome, [hashtable]$Manifest = (Get-ManagedGraalManifest))
    $TurboismHome = [System.IO.Path]::GetFullPath($TurboismHome).TrimEnd('\', '/')
    if (-not (Test-CubismNormalDirectory $TurboismHome)) { throw "Turboism home is not an existing ordinary directory: $TurboismHome" }
    if ($Manifest.Size -le 0 -or $Manifest.Size -gt 400MB -or $Manifest.Sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw "Invalid pinned GraalVM manifest." }
    $graalRoot = Join-Path $TurboismHome "graal"
    $runtimePath = Join-Path $graalRoot "runtime"
    Assert-ManagedGraalPath $TurboismHome $runtimePath
    $plan = New-ManagedGraalConfig $TurboismHome $runtimePath
    [void][System.IO.Directory]::CreateDirectory($graalRoot)
    $lockPath = Join-Path $graalRoot ".installer.lock"
    Assert-ManagedGraalPath $TurboismHome $lockPath
    $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $staging = Join-Path $graalRoot (".install-" + [guid]::NewGuid().ToString("N"))
    $keepStaging = $false; $movedPrevious = $false; $activated = $false
    try {
        foreach ($marker in @(".runtime-activation", ".runtime-previous")) {
            if (Test-Path -LiteralPath (Join-Path $graalRoot $marker)) { throw "An interrupted GraalVM activation requires recovery before reinstalling." }
        }
        [void][System.IO.Directory]::CreateDirectory($staging)
        $archive = Join-Path $staging "archive.zip"
        Receive-ManagedGraalArchive $Manifest $archive
        Test-ManagedGraalCancellation
        Write-ManagedGraalProgress "VERIFYING"
        if ((Get-Item -LiteralPath $archive).Length -ne $Manifest.Size -or
            (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $Manifest.Sha256) { throw "GraalVM archive size or SHA-256 does not match the pinned release." }
        $extracted = Join-Path $staging "extracted"
        [void][System.IO.Directory]::CreateDirectory($extracted)
        Write-ManagedGraalProgress "EXTRACTING"
        $candidate = Expand-ManagedGraalArchive $archive $extracted
        Assert-ManagedGraalRelease $candidate $Manifest
        Test-ManagedGraalCancellation
        if ($null -ne $script:graalUi) { $script:graalUi.Cancel.Enabled = $false }
        $previous = Join-Path $staging "previous"
        try {
            Assert-ManagedGraalPath $TurboismHome $runtimePath
            if (Test-Path -LiteralPath $runtimePath) {
                if (-not (Test-CubismNormalDirectory $runtimePath)) { throw "Managed GraalVM runtime is not an ordinary directory." }
                [System.IO.Directory]::Move($runtimePath, $previous); $movedPrevious = $true
            }
            [System.IO.Directory]::Move($candidate, $runtimePath); $activated = $true
            Save-ManagedGraalConfig $TurboismHome $plan $staging
        } catch {
            $failure = $_
            try {
                if ($activated) { [System.IO.Directory]::Move($runtimePath, (Join-Path $staging "failed-runtime")) }
                if ($movedPrevious) { [System.IO.Directory]::Move($previous, $runtimePath) }
            } catch {
                $keepStaging = $true
                Write-ManagedGraalLog "GRAAL_INSTALL_ROLLBACK_FAILED previous=$previous"
            }
            throw $failure
        }
        Write-ManagedGraalProgress "READY"
        return (Join-Path $runtimePath "bin/java.exe")
    } finally {
        if (-not $keepStaging) {
            try { Remove-ManagedGraalStaging $TurboismHome $staging }
            catch { Write-ManagedGraalLog "GRAAL_INSTALL_CLEANUP_PENDING path=$staging" }
        }
        $lock.Dispose()
    }
}

function New-ManagedGraalWindow {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $strings = @{
        en = @{ Title = "Turboism - GraalVM installation"; DOWNLOADING = "Downloading GraalVM"; EXTRACTING = "Extracting runtime files..."; VERIFYING = "Verifying downloaded files..."; READY = "GraalVM is installed."; Cancel = "Cancel" }
        zh = @{ Title = "Turboism - GraalVM 安装"; DOWNLOADING = "正在下载 GraalVM"; EXTRACTING = "正在解压运行时文件..."; VERIFYING = "正在校验下载文件..."; READY = "GraalVM 已安装。"; Cancel = "取消" }
        ja = @{ Title = "Turboism - GraalVM インストール"; DOWNLOADING = "GraalVM をダウンロード中"; EXTRACTING = "ランタイムを展開しています..."; VERIFYING = "ダウンロードしたファイルを検証しています..."; READY = "GraalVM をインストールしました。"; Cancel = "キャンセル" }
        ko = @{ Title = "Turboism - GraalVM 설치"; DOWNLOADING = "GraalVM 다운로드 중"; EXTRACTING = "런타임 파일 압축 해제 중..."; VERIFYING = "다운로드한 파일 검증 중..."; READY = "GraalVM이 설치되었습니다."; Cancel = "취소" }
    }
    $language = [System.Threading.Thread]::CurrentThread.CurrentUICulture.TwoLetterISOLanguageName
    if (-not $strings.ContainsKey($language)) { $language = "en" }
    $s = $strings[$language]
    $form = [System.Windows.Forms.Form]::new(); $form.Text = $s.Title
    $form.Size = [System.Drawing.Size]::new(560, 220); $form.StartPosition = "CenterScreen"; $form.ControlBox = $false
    $status = [System.Windows.Forms.Label]::new(); $status.Text = $s.DOWNLOADING
    $status.Location = [System.Drawing.Point]::new(20, 20); $status.Size = [System.Drawing.Size]::new(510, 26); $form.Controls.Add($status)
    $progress = [System.Windows.Forms.ProgressBar]::new(); $progress.Maximum = 1000; $progress.Style = "Marquee"
    $progress.Location = [System.Drawing.Point]::new(20, 58); $progress.Size = [System.Drawing.Size]::new(510, 24); $form.Controls.Add($progress)
    $detail = [System.Windows.Forms.Label]::new(); $detail.Location = [System.Drawing.Point]::new(20, 92); $detail.Size = [System.Drawing.Size]::new(510, 24); $form.Controls.Add($detail)
    $cancel = [System.Windows.Forms.Button]::new(); $cancel.Text = $s.Cancel
    $cancel.Location = [System.Drawing.Point]::new(390, 130); $cancel.Size = [System.Drawing.Size]::new(140, 30); $form.Controls.Add($cancel)
    $cancel.Add_Click({ $script:graalCancelled = $true; $script:graalUi.Cancel.Enabled = $false })
    $script:graalUi = @{ Form = $form; Status = $status; Progress = $progress; Detail = $detail; Cancel = $cancel; Strings = $s; LastTime = [DateTime]::UtcNow; LastBytes = 0L }
    $form.Show(); [System.Windows.Forms.Application]::DoEvents()
}

# The same production functions are used by the offline regression suite.
if ($MyInvocation.InvocationName -eq '.') { return }
$result = 1
try {
    $turboismHome = [System.IO.Path]::GetFullPath($HomePath).TrimEnd('\', '/')
    if (-not (Test-CubismNormalDirectory $turboismHome)) { throw "Turboism home is not an existing ordinary directory: $turboismHome" }
    $logs = Join-Path $turboismHome "logs\installer"
    Assert-ManagedGraalPath $turboismHome $logs
    [void][System.IO.Directory]::CreateDirectory($logs)
    $script:graalLogPath = Join-Path $logs "managed-graal-install.log"
    Assert-ManagedGraalPath $turboismHome $script:graalLogPath
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    if ($env:OS -ne "Windows_NT" -or $architecture -ine "AMD64") { throw "Automatic GraalVM installation supports Windows x64 only." }
    if ($Gui) { New-ManagedGraalWindow }
    [void](Install-ManagedGraalRuntime $turboismHome)
    $result = 0
} catch [System.OperationCanceledException] {
    Write-ManagedGraalLog "GRAAL_INSTALL_CANCELLED"
    $result = 2
} catch {
    Write-ManagedGraalLog ("GRAAL_INSTALL_EXCEPTION " + $_.Exception.Message)
    [Console]::Error.WriteLine($_.Exception.Message)
    $result = 1
} finally {
    Write-ManagedGraalLog ("GRAAL_INSTALL_EXIT code=" + $result)
    if ($null -ne $script:graalUi) { $script:graalUi.Form.Dispose(); $script:graalUi = $null }
}
exit $result
