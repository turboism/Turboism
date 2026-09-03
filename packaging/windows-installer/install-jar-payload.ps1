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

foreach ($entry in $entries) {
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
