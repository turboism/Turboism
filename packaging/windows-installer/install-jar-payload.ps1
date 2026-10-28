[CmdletBinding()]
param(
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot,

    [Parameter(Mandatory = $true)]
    [string]$ManifestPath,

    [string]$PlanRoot,

    [switch]$PlanOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PayloadPath {
    param(
        [string]$Root,
        [string]$Relative
    )

    if ([string]::IsNullOrWhiteSpace($Relative) -or
        [System.IO.Path]::IsPathRooted($Relative) -or
        $Relative.Contains("\")) {
        throw "Installer payload manifest contains an invalid relative path."
    }
    $segments = $Relative.Split('/')
    if (@($segments | Where-Object { $_ -eq "" -or $_ -eq "." -or $_ -eq ".." }).Count -ne 0) {
        throw "Installer payload manifest contains an unsafe relative path."
    }

    $rootPath = [System.IO.Path]::GetFullPath($Root)
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootPath ($segments -join [System.IO.Path]::DirectorySeparatorChar)))
    $prefix = $rootPath.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Installer payload path escapes its root."
    }
    return $candidate
}

function Read-PayloadManifest {
    param([string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not [System.IO.File]::Exists($fullPath)) {
        throw "Installer payload manifest does not exist."
    }
    $entries = @()
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($line in [System.IO.File]::ReadAllLines($fullPath)) {
        if ($line -notmatch '^([0-9a-fA-F]{64})  ([A-Za-z0-9._/-]+)$') {
            throw "Installer payload manifest contains an invalid entry."
        }
        $relative = $Matches[2]
        if (-not $seen.Add($relative)) {
            throw "Installer payload manifest contains a duplicate path."
        }
        $entries += [pscustomobject]@{
            Hash = $Matches[1].ToLowerInvariant()
            Relative = $relative
        }
    }
    if ($entries.Count -eq 0) {
        throw "Installer payload manifest is empty."
    }
    return $entries
}

function Get-PayloadFileHash {
    param([string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Test-PayloadFileUnchanged {
    param(
        [string]$Path,
        [string]$ExpectedHash
    )

    if ([System.IO.Directory]::Exists($Path)) {
        throw "Installer payload destination is a directory."
    }
    if (-not [System.IO.File]::Exists($Path)) {
        return $false
    }
    $attributes = [System.IO.File]::GetAttributes($Path)
    if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Installer payload destination is a reparse point."
    }
    return (Get-PayloadFileHash $Path).Equals(
        $ExpectedHash,
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Assert-PayloadDirectory {
    param([string]$Path)

    if ([System.IO.File]::Exists($Path)) {
        throw "Installer payload directory path is a file."
    }
    if ([System.IO.Directory]::Exists($Path)) {
        $attributes = [System.IO.File]::GetAttributes($Path)
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Installer payload directory is a reparse point."
        }
        return
    }
    [void][System.IO.Directory]::CreateDirectory($Path)
}

function Assert-PayloadParentChain {
    param(
        [string]$Root,
        [string]$FilePath
    )

    $rootPath = [System.IO.Path]::GetFullPath($Root)
    Assert-PayloadDirectory $rootPath
    $parent = [System.IO.Path]::GetDirectoryName($FilePath)
    $relativeParent = $parent.Substring($rootPath.Length).TrimStart(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $current = $rootPath
    if (-not [string]::IsNullOrWhiteSpace($relativeParent)) {
        foreach ($segment in $relativeParent.Split([System.IO.Path]::DirectorySeparatorChar)) {
            $current = Join-Path $current $segment
            Assert-PayloadDirectory $current
        }
    }
}

# Installer packages use module filenames; marketplace updates use plugin IDs.
# Reconcile by embedded identity only after every payload file has been verified.
function Get-PayloadPluginId {
    param([string]$Path)

    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try {
            $entry = $archive.GetEntry("META-INF/turboism/plugin.json")
            if ($null -eq $entry -or $entry.Length -gt 1MB) { return $null }
            $reader = New-Object System.IO.StreamReader($entry.Open())
            try { $descriptor = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            if ($null -eq $descriptor) { return $null }
            $property = $descriptor.PSObject.Properties["id"]
            if ($null -eq $property -or $property.Value -isnot [string] -or
                [string]::IsNullOrWhiteSpace($property.Value)) { return $null }
            return $property.Value
        }
        finally { $archive.Dispose() }
    }
    catch {
        # Unreadable/unrelated user packages never authorize moving a file.
        return $null
    }
}

function Backup-PayloadPluginDuplicates {
    param([string]$Root, [object[]]$ManifestEntries)

    $pluginEntries = @($ManifestEntries | Where-Object { $_.Relative -match '^plugins/[^/]+\.jar$' })
    if ($pluginEntries.Count -eq 0) { return }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $installed = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    foreach ($entry in $pluginEntries) {
        $path = Resolve-PayloadPath $Root $entry.Relative
        Assert-PayloadParentChain $Root $path
        if (-not (Test-PayloadFileUnchanged $path $entry.Hash)) {
            throw "Cannot reconcile plugins before the payload is verified."
        }
        $id = Get-PayloadPluginId $path
        if ([string]::IsNullOrWhiteSpace($id) -or $installed.ContainsKey($id)) {
            throw "Installer plugin payload contains a missing or duplicate plugin ID."
        }
        $installed.Add($id, $path)
    }

    $plugins = Join-Path $Root "plugins"
    $backupRelative = "backups/plugins/" + [Guid]::NewGuid().ToString("N")
    foreach ($candidate in [System.IO.Directory]::GetFiles($plugins)) {
        if (-not [System.IO.Path]::GetExtension($candidate).Equals(".jar", [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        if (([System.IO.File]::GetAttributes($candidate) -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            continue
        }
        $id = Get-PayloadPluginId $candidate
        if ([string]::IsNullOrWhiteSpace($id) -or -not $installed.ContainsKey($id)) { continue }
        if ($candidate.Equals($installed[$id], [System.StringComparison]::OrdinalIgnoreCase)) { continue }
        $backup = Resolve-PayloadPath $Root ($backupRelative + "/" + [System.IO.Path]::GetFileName($candidate))
        Assert-PayloadParentChain $Root $backup
        [System.IO.File]::Move($candidate, $backup)
        Write-Output "BACKUP_DUPLICATE|$id|$candidate|$backup"
    }
}

$destination = [System.IO.Path]::GetFullPath($DestinationRoot)
Assert-PayloadDirectory $destination
$entries = @(Read-PayloadManifest $ManifestPath)

if ($PlanOnly) {
    if ([string]::IsNullOrWhiteSpace($PlanRoot)) {
        throw "Installer payload plan root is required."
    }
    $plan = [System.IO.Path]::GetFullPath($PlanRoot)
    Assert-PayloadDirectory $plan
    foreach ($staleMarker in [System.IO.Directory]::GetFiles($plan, "*.need")) {
        [System.IO.File]::Delete($staleMarker)
    }

    for ($index = 0; $index -lt $entries.Count; $index++) {
        $entry = $entries[$index]
        $skipMarker = Join-Path $plan (("{0:D4}.skip" -f $index))
        if ([System.IO.File]::Exists($skipMarker)) {
            Write-Output "SKIP_UNSELECTED|$($entry.Relative)"
            continue
        }
        $destinationFile = Resolve-PayloadPath $destination $entry.Relative
        if (Test-PayloadFileUnchanged $destinationFile $entry.Hash) {
            Write-Output "SKIP|$($entry.Relative)"
        }
        else {
            $marker = Join-Path $plan (("{0:D4}.need" -f $index))
            [System.IO.File]::WriteAllText($marker, "")
            Write-Output "NEED|$($entry.Relative)"
        }
    }
    return
}

if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    throw "Installer payload source is required."
}
$source = [System.IO.Path]::GetFullPath($SourceRoot)
Assert-PayloadDirectory $source
$plan = if ([string]::IsNullOrWhiteSpace($PlanRoot)) { $null } else { [System.IO.Path]::GetFullPath($PlanRoot) }
$activeEntries = @()

for ($index = 0; $index -lt $entries.Count; $index++) {
    $entry = $entries[$index]
    if ($null -ne $plan -and [System.IO.File]::Exists((Join-Path $plan (("{0:D4}.skip" -f $index))))) {
        Write-Output "SKIP_UNSELECTED|$($entry.Relative)"
        continue
    }
    $activeEntries += $entry
    $destinationFile = Resolve-PayloadPath $destination $entry.Relative
    if (Test-PayloadFileUnchanged $destinationFile $entry.Hash) {
        Write-Output "SKIP|$($entry.Relative)"
        continue
    }

    $sourceFile = Resolve-PayloadPath $source $entry.Relative
    if (-not [System.IO.File]::Exists($sourceFile) -or [System.IO.Directory]::Exists($sourceFile)) {
        throw "A required installer payload source file was not extracted."
    }
    $sourceAttributes = [System.IO.File]::GetAttributes($sourceFile)
    if (($sourceAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Installer payload source is a reparse point."
    }
    if (-not (Get-PayloadFileHash $sourceFile).Equals(
        $entry.Hash,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Installer payload source checksum does not match its manifest."
    }

    Assert-PayloadParentChain $destination $destinationFile
    if (Test-PayloadFileUnchanged $destinationFile $entry.Hash) {
        Write-Output "SKIP|$($entry.Relative)"
        continue
    }
    [System.IO.File]::Copy($sourceFile, $destinationFile, $true)
    if (-not (Test-PayloadFileUnchanged $destinationFile $entry.Hash)) {
        throw "Installer payload destination checksum does not match after copy."
    }
    Write-Output "WRITE|$($entry.Relative)"
}

Backup-PayloadPluginDuplicates $destination $activeEntries
