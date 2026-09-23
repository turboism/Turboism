# Optional GraalJS native isolate and Truffle payload. Never starts Java.
[CmdletBinding()]
param(
    [Alias('Home')][string]$HomePath = $PSScriptRoot,
    [string]$OfflineSource = ''
)

function Assert-ScriptEngineDirectory {
    param([string]$Path, [switch]$Create)
    if (Test-Path -LiteralPath $Path) {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        if (-not $item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Script engine directory is not a normal directory: $Path"
        }
    }
    elseif ($Create) { [void][System.IO.Directory]::CreateDirectory($Path) }
    else { throw "Script engine directory is missing: $Path" }
}

function Test-ScriptEngineFile {
    param([string]$Path, [object]$Entry)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Script engine file is not a normal file: $Path"
    }
    return [int64]$item.Length -eq [int64]$Entry.bytes -and
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash -ieq $Entry.sha256
}

function Read-ScriptEngineManifest {
    param([string]$HomePath)
    Assert-ScriptEngineDirectory $HomePath
    $path = Join-Path $HomePath 'script-engine.json'
    $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Length -gt 8192 -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Optional script engine manifest is unsafe or oversized'
    }
    $document = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json -ErrorAction Stop
    if ($null -eq $document -or
        (@($document.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'artifacts,format,schemaVersion,version' -or
        $document.format -cne 'turboism.optional-script-engine' -or
        ($document.schemaVersion -isnot [int] -and $document.schemaVersion -isnot [long]) -or $document.schemaVersion -ne 1 -or
        $document.version -isnot [string] -or $document.version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?$' -or
        $document.artifacts -isnot [array] -or $document.artifacts.Count -ne 2) {
        throw 'Optional script engine manifest schema is invalid'
    }
    $expected = @('js-isolate-windows-amd64-community-' + $document.version + '.jar'; 'truffle-api-' + $document.version + '.jar')
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($entry in $document.artifacts) {
        if ($null -eq $entry -or
            (@($entry.PSObject.Properties.Name | Sort-Object) -join ',') -cne 'bytes,name,sha256' -or
            $entry.name -isnot [string] -or $expected -cnotcontains $entry.name -or -not $seen.Add($entry.name) -or
            ($entry.bytes -isnot [int] -and $entry.bytes -isnot [long]) -or $entry.bytes -le 0 -or $entry.bytes -gt 134217728 -or
            $entry.sha256 -isnot [string] -or $entry.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
            throw 'Optional script engine artifact pin is invalid'
        }
    }
    return $document
}

function Get-TurboismScriptEngineStatus {
    param([string]$HomePath)
    $manifest = Read-ScriptEngineManifest $HomePath
    $root = Join-Path $HomePath 'graal'
    if (-not (Test-Path -LiteralPath $root)) { return [pscustomobject]@{ Status='Absent'; Manifest=$manifest } }
    Assert-ScriptEngineDirectory $root
    $lib = Join-Path $root 'lib'
    if (-not (Test-Path -LiteralPath $lib)) { return [pscustomobject]@{ Status='Absent'; Manifest=$manifest } }
    Assert-ScriptEngineDirectory $lib
    $present = 0
    $verified = 0
    foreach ($entry in $manifest.artifacts) {
        $path = Join-Path $lib $entry.name
        if (Test-Path -LiteralPath $path) { $present++ }
        if (Test-ScriptEngineFile $path $entry) { $verified++ }
    }
    $status = if ($verified -eq 2) { 'Ready' } elseif ($present -eq 0) { 'Absent' } else { 'Invalid' }
    return [pscustomobject]@{ Status=$status; Manifest=$manifest }
}

function Receive-ScriptEngineArtifact {
    param([string]$Version, [object]$Entry, [string]$Destination)
    # URLs are derived from the two allowed artifact identities, never supplied
    # by the manifest. Redirects cannot move a pinned download to another host.
    $artifact = $Entry.name.Substring(0, $Entry.name.Length - $Version.Length - 5)
    # org.graalvm.polyglot supplies the dependency POM; the native isolate JAR
    # itself is published in org.graalvm.js (as resolved by Gradle).
    $group = if ($artifact -eq 'truffle-api') { 'org/graalvm/truffle' } else { 'org/graalvm/js' }
    $url = "https://repo.maven.apache.org/maven2/$group/$artifact/$Version/$($Entry.name)"
    Add-Type -AssemblyName System.Net.Http -ErrorAction Stop
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $response = $null; $inputStream = $null; $outputStream = $null
    $cancel = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromMinutes(5))
    try {
        Write-Host "Downloading $($Entry.name) ($($Entry.bytes) bytes)"
        $response = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cancel.Token).GetAwaiter().GetResult()
        [void]$response.EnsureSuccessStatusCode()
        if ($null -ne $response.Content.Headers.ContentLength -and $response.Content.Headers.ContentLength -ne $Entry.bytes) {
            throw 'Script engine download length differs from its pin'
        }
        $inputStream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $outputStream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $buffer = New-Object byte[] 65536
        $total = [int64]0
        while (($count = $inputStream.ReadAsync($buffer, 0, $buffer.Length, $cancel.Token).GetAwaiter().GetResult()) -gt 0) {
            $total += $count
            if ($total -gt $Entry.bytes) { throw 'Script engine download exceeded its pinned byte budget' }
            $outputStream.Write($buffer, 0, $count)
        }
        if ($total -ne $Entry.bytes) { throw 'Script engine download was truncated' }
    }
    finally {
        if ($null -ne $outputStream) { $outputStream.Dispose() }
        if ($null -ne $inputStream) { $inputStream.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $cancel.Dispose(); $client.Dispose(); $handler.Dispose()
    }
}

function Install-TurboismScriptEngine {
    param([string]$HomePath, [string]$OfflineSource = '')
    $manifest = Read-ScriptEngineManifest $HomePath
    $root = Join-Path $HomePath 'graal'
    Assert-ScriptEngineDirectory $root -Create
    $lib = Join-Path $root 'lib'
    Assert-ScriptEngineDirectory $lib -Create
    $lockPath = Join-Path $root '.script-engine.lock'
    if (Test-Path -LiteralPath $lockPath) {
        $lockItem = Get-Item -LiteralPath $lockPath -Force -ErrorAction Stop
        if ($lockItem.PSIsContainer -or ($lockItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Script engine lock is unsafe' }
    }
    $lock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $work = Join-Path $root ('.script-engine-' + [guid]::NewGuid().ToString('N'))
    $published = [System.Collections.Generic.List[object]]::new()
    $preserveRecovery = $false
    try {
        $pending = @($manifest.artifacts | Where-Object { -not (Test-ScriptEngineFile (Join-Path $lib $_.name) $_) })
        if ($pending.Count -eq 0) { Write-Host 'SCRIPT_ENGINE_READY already-installed'; return }
        Assert-ScriptEngineDirectory $work -Create
        # Obtain and verify every required artifact BEFORE changing any live file.
        foreach ($entry in $pending) {
            $staged = Join-Path $work $entry.name
            if (-not [string]::IsNullOrWhiteSpace($OfflineSource)) {
                Assert-ScriptEngineDirectory $OfflineSource
                $source = Join-Path $OfflineSource $entry.name
                if (-not (Test-ScriptEngineFile $source $entry)) { throw "Offline script dependency is missing or has a bad checksum: $source" }
                [System.IO.File]::Copy($source, $staged, $false)
            }
            else { Receive-ScriptEngineArtifact -Version $manifest.version -Entry $entry -Destination $staged }
            if (-not (Test-ScriptEngineFile $staged $entry)) { throw "Script dependency checksum mismatch: $($entry.name)" }
        }
        foreach ($entry in $pending) {
            Assert-ScriptEngineDirectory $root
            Assert-ScriptEngineDirectory $lib
            $destination = Join-Path $lib $entry.name
            if (Test-ScriptEngineFile $destination $entry) { continue }
            $staged = Join-Path $work $entry.name
            $backup = $null
            if (Test-Path -LiteralPath $destination) {
                $backup = $staged + '.backup'
                [System.IO.File]::Replace($staged, $destination, $backup)
            }
            else { [System.IO.File]::Move($staged, $destination) }
            [void]$published.Add([pscustomobject]@{ Destination=$destination; Backup=$backup; Entry=$entry })
            if (-not (Test-ScriptEngineFile $destination $entry)) { throw 'Published script dependency failed verification' }
        }
        if ((Get-TurboismScriptEngineStatus $HomePath).Status -ne 'Ready') { throw 'Optional script engine installation is incomplete' }
        Write-Host 'SCRIPT_ENGINE_READY installed-and-verified'
    }
    catch {
        $failure = $_
        for ($index = $published.Count - 1; $index -ge 0; $index--) {
            $record = $published[$index]
            try {
                if (-not (Test-ScriptEngineFile $record.Destination $record.Entry)) { throw 'Destination changed during rollback' }
                if ($null -ne $record.Backup) { [System.IO.File]::Replace($record.Backup, $record.Destination, $null) }
                else { [System.IO.File]::Delete($record.Destination) }
            }
            catch { $preserveRecovery = $true; Write-Warning "Script engine recovery files preserved at $work" }
        }
        throw $failure
    }
    finally {
        if (-not $preserveRecovery -and (Test-Path -LiteralPath $work)) { Remove-Item -LiteralPath $work -Recurse -Force }
        $lock.Dispose()
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    try { Install-TurboismScriptEngine -HomePath $HomePath -OfflineSource $OfflineSource }
    catch { Write-Error ("SCRIPT_ENGINE_INSTALL_FAILED " + $_.Exception.Message); exit 1 }
}
