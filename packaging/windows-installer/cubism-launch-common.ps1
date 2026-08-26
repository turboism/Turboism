# -*- coding: utf-8 -*-
# Shared, synthetic-testable Windows Cubism inventory and managed-launch helpers.
# This file never writes inside a Cubism root.

$script:CubismStateFormat = "turboism.cubism.installation-state"
$script:CubismStateSchemaVersion = 1
$script:CubismMaxRoots = 256
$script:CubismMaxStateBytes = 65536
$script:CubismMaxStateEntries = 256
$script:CubismMaxShortcutEntries = 512
$script:CubismMaxStateFieldLength = 4096
$script:CubismMaxJdkOptionText = 16384
$script:CubismMaxJdkOptionTokens = 256
$script:CubismMaxJdkOptionLength = 4096
$script:CubismMaxLaunchArguments = 64
$script:CubismScriptRoot = $PSScriptRoot
$script:CubismMaxScanResults = 256
$script:CubismMaxScanEntries = 4096
$script:CubismMaxScanDrives = 26

function ConvertTo-CubismCanonicalRoot {
    param([string]$Root)
    if ([string]::IsNullOrWhiteSpace($Root)) { return $null }
    try {
        $full = [System.IO.Path]::GetFullPath($Root.Trim())
        if (Test-Path -LiteralPath $full -PathType Container) {
            $full = (Get-Item -LiteralPath $full -Force -ErrorAction Stop).FullName
        }
        return $full.TrimEnd('\', '/')
    }
    catch { return $null }
}

function Get-CubismRootKey {
    param([string]$Root)
    $canonical = ConvertTo-CubismCanonicalRoot $Root
    if ($null -eq $canonical) { return $null }
    return $canonical.ToUpperInvariant()
}

function Test-CubismFixedDrive {
    param([string]$Root)
    try {
        $pathRoot = [System.IO.Path]::GetPathRoot($Root)
        if ([string]::IsNullOrWhiteSpace($pathRoot) -or $pathRoot.StartsWith('\\')) { return $false }
        return ([System.IO.DriveInfo]::new($pathRoot).DriveType -eq [System.IO.DriveType]::Fixed)
    }
    catch { return $false }
}

function Get-CubismVersionFromPath {
    param([string]$Root)
    # This is a family hint only. It is never an exact patch-version claim.
    $leaf = Split-Path -Leaf ($Root.TrimEnd('\', '/'))
    $match = [regex]::Match($leaf, '(?i)^(?:Live2D\s+)?Cubism(?:\s+Editor)?\s+5\.(2|3)(?:\.[0-9]+)?(?:\s+.*)?$')
    if ($match.Success) { return "5.$($match.Groups[1].Value)" }
    return $null
}

function Test-CubismPlausiblePath {
    param([string]$Root)
    return $null -ne (Get-CubismVersionFromPath $Root) -or (Test-CubismRequiredFileLayout $Root)
}

function Test-CubismRequiredFileLayout {
    param([string]$Root)
    if ([string]::IsNullOrWhiteSpace($Root)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $Root "CubismEditor5.bat") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root "app\jre\bin\java.exe") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root "app\lib\Live2D_Cubism.jar") -PathType Leaf)
}

function Test-CubismRequiredFileShape {
    param([string]$Root)
    if ($null -eq $Root -or -not (Test-CubismFixedDrive $Root)) { return $false }
    return Test-CubismRequiredFileLayout $Root
}

function Test-CubismAutoCandidatePath {
    param([string]$Root)
    if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-CubismFixedDrive $Root)) { return $false }
    return Test-CubismPlausiblePath $Root
}

function Get-CubismD3DBat {
    param([string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return $null }
    try {
        $batMatches = @(
            Get-ChildItem -LiteralPath $Root -File -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(?i)^CubismEditor5[-_]?D3D\.bat$' } |
                Sort-Object Name
        )
        if ($batMatches.Count -eq 0) { return $null }
        return $batMatches[0].FullName
    }
    catch { return $null }
}

function New-CubismInstallationCandidate {
    param(
        [string]$Root,
        [string]$Source = "discovery"
    )

    $canonical = ConvertTo-CubismCanonicalRoot $Root
    if ($null -eq $canonical) {
        return [pscustomobject]@{
            Root = $Root; CanonicalRoot = $Root; Key = $Root; Version = ""; Source = $Source
            Status = "Invalid"; Reason = "The path is not valid."; Selectable = $false
            OfficialBat = $null; D3DBat = $null; Java = $null; ApplicationJar = $null; Selected = $false
        }
    }

    $version = Get-CubismVersionFromPath $canonical
    $officialBat = Join-Path $canonical "CubismEditor5.bat"
    $java = Join-Path $canonical "app\jre\bin\java.exe"
    $applicationJar = Join-Path $canonical "app\lib\Live2D_Cubism.jar"
    $missing = @()
    if (-not (Test-CubismFixedDrive $canonical)) { $missing += "fixed local drive" }
    if (-not (Test-Path -LiteralPath $canonical -PathType Container)) { $missing += "root directory" }
    else {
        try {
            $rootItem = Get-Item -LiteralPath $canonical -Force -ErrorAction Stop
            if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                $missing += "reparse-point root"
            }
        }
        catch { $missing += "root directory" }
    }
    if (-not (Test-Path -LiteralPath $officialBat -PathType Leaf)) { $missing += "CubismEditor5.bat" }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { $missing += "bundled Java launcher" }
    if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) { $missing += "Cubism application JAR" }

    if ($null -eq $version) {
        $status = "Unsupported"
        $reason = "Only Cubism 5.2 and 5.3 family candidates are selectable; exact patch admission remains host-owned."
    }
    elseif ($missing.Count -gt 0) {
        $status = "Invalid"
        $reason = "Missing: " + ($missing -join ", ") + "."
    }
    else {
        $status = "Ready"
        $reason = "Family candidate only; runtime exact-version admission is separate."
    }

    return [pscustomobject]@{
        Root = $canonical; CanonicalRoot = $canonical; Key = (Get-CubismRootKey $canonical)
        Version = $(if ($null -eq $version) { "" } else { $version }); Source = $Source
        Status = $status; Reason = $reason; Selectable = ($status -eq "Ready")
        OfficialBat = $officialBat; D3DBat = (Get-CubismD3DBat $canonical)
        Java = $java; ApplicationJar = $applicationJar; Selected = $false
    }
}

function Test-CubismScanContainer {
    param([string]$Name)
    return [regex]::IsMatch($Name, '(?i)^(?:Program Files(?: \(x86\))?|ProgramData|Users|Applications?|Apps?|Software|Games|Programs|Live2D(?:\s+Cubism.*)?|Cubism.*)$')
}

function Get-CubismDirectories {
    param(
        [string]$Parent,
        [int]$MaxEntries = $script:CubismMaxScanEntries,
        [int]$MaxResults = $script:CubismMaxScanResults
    )
    if ($MaxEntries -lt 1 -or $MaxResults -lt 1) { return @() }
    try {
        $items = [System.Collections.Generic.List[object]]::new()
        $inspected = 0
        foreach ($item in Get-ChildItem -LiteralPath $Parent -Directory -Force -ErrorAction SilentlyContinue) {
            $inspected++
            if ($inspected -gt $MaxEntries) { break }
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
                -not (Test-CubismScanContainer $item.Name)) { continue }
            [void]$items.Add($item)
            if ($items.Count -ge $MaxResults) { break }
        }
        return @($items | Sort-Object FullName)
    }
    catch { return @() }
}

function Get-CubismShallowDiscoveryRoots {
    param(
        [string[]]$DriveRoots = @(),
        [int]$MaxRoots = $script:CubismMaxRoots
    )
    $rootLimit = [Math]::Min([Math]::Max(1, $MaxRoots), $script:CubismMaxRoots)
    $roots = New-Object System.Collections.Generic.List[string]
    $keys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = {
        param([string]$Root)
        if ($roots.Count -ge $rootLimit -or [string]::IsNullOrWhiteSpace($Root)) { return }
        $canonical = ConvertTo-CubismCanonicalRoot $Root
        if ($null -eq $canonical -or -not (Test-CubismPlausiblePath $canonical)) { return }
        if ($keys.Add($canonical)) { [void]$roots.Add($canonical) }
    }
    foreach ($driveRoot in @($DriveRoots | Select-Object -First $script:CubismMaxScanDrives)) {
        if (-not (Test-CubismFixedDrive $driveRoot)) { continue }
        try {
            $driveItem = Get-Item -LiteralPath $driveRoot -Force -ErrorAction Stop
            if (-not $driveItem.PSIsContainer -or
                ($driveItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
        }
        catch { continue }
        foreach ($top in Get-CubismDirectories $driveRoot) {
            & $add $top.FullName
            if ($roots.Count -ge $rootLimit) { break }
            foreach ($one in Get-CubismDirectories $top.FullName) {
                & $add $one.FullName
                if ($roots.Count -ge $rootLimit) { break }
                foreach ($two in Get-CubismDirectories $one.FullName) {
                    & $add $two.FullName
                    if ($roots.Count -ge $rootLimit) { break }
                }
                if ($roots.Count -ge $rootLimit) { break }
            }
            if ($roots.Count -ge $rootLimit) { break }
        }
        if ($roots.Count -ge $rootLimit) { break }
    }
    return @($roots)
}

function Get-CubismDiscoveryRoots {
    param(
        [string[]]$SavedRoots = @(),
        [string[]]$ManualRoots = @(),
        [switch]$IncludeMetadata
    )
    $roots = New-Object System.Collections.Generic.List[string]
    $keys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $automaticKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = {
        param([string]$Root, [bool]$Explicit)
        if ($roots.Count -ge $script:CubismMaxRoots -or [string]::IsNullOrWhiteSpace($Root)) { return }
        $canonical = ConvertTo-CubismCanonicalRoot $Root
        if ($null -eq $canonical) { return }
        if (-not $Explicit) {
            if (-not (Test-CubismAutoCandidatePath $canonical)) { return }
            $automaticKey = Get-CubismRootKey $canonical
            if ($null -eq $automaticKey) { return }
            [void]$automaticKeys.Add($automaticKey)
        }
        if ($keys.Add($canonical)) { [void]$roots.Add($canonical) }
    }

    # Explicit/saved roots are retained even when currently invalid so the UI can
    # report them. Automatic sources are admitted only as plausible candidates.
    foreach ($root in @($SavedRoots)) { & $add $root $true }
    if (-not [string]::IsNullOrWhiteSpace($env:CUBISM_ROOT)) { & $add $env:CUBISM_ROOT $true }
    foreach ($root in @($ManualRoots)) { & $add $root $true }

    $registryPaths = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    foreach ($path in $registryPaths) {
        try {
            $entries = @(Get-ItemProperty -Path $path -ErrorAction SilentlyContinue |
                Where-Object {
                    $identity = @($_.DisplayName, $_.Publisher, $_.InstallLocation, $_.InstallDir) -join " "
                    $identity -match '(?i)Live2D|Cubism'
                } | Sort-Object @{Expression={ [string]$_.DisplayName }}, @{Expression={ [string]$_.InstallLocation }})
            foreach ($entry in $entries) {
                foreach ($property in @("InstallLocation", "InstallDir", "Location")) {
                    $value = $entry.$property
                    if ($value -is [string] -and -not [string]::IsNullOrWhiteSpace($value)) { & $add $value $false }
                }
            }
        }
        catch { }
    }

    $knownBases = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:ProgramW6432, $env:LOCALAPPDATA) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique
    foreach ($base in $knownBases) {
        foreach ($relative in @(
            "Live2D Cubism 5.2", "Live2D Cubism 5.3", "Live2D\Cubism 5.2",
            "Live2D\Cubism 5.3", "Cubism 5.2", "Cubism 5.3"
        )) { & $add (Join-Path $base $relative) $false }
        foreach ($child in Get-CubismDirectories $base) {
            & $add $child.FullName $false
            foreach ($grandchild in Get-CubismDirectories $child.FullName) {
                & $add $grandchild.FullName $false
            }
        }
    }

    try {
        $driveRoots = @([System.IO.DriveInfo]::GetDrives() |
            Where-Object { $_.DriveType -eq [System.IO.DriveType]::Fixed } |
            Sort-Object Name |
            Select-Object -First $script:CubismMaxScanDrives |
            ForEach-Object { $_.RootDirectory.FullName })
        foreach ($candidateRoot in Get-CubismShallowDiscoveryRoots -DriveRoots $driveRoots) {
            & $add $candidateRoot $false
            if ($roots.Count -ge $script:CubismMaxRoots) { break }
        }
    }
    catch { }

    if ($IncludeMetadata) {
        return [pscustomobject]@{
            Roots = @($roots)
            AutomaticRootKeys = @($automaticKeys | ForEach-Object { [string]$_ })
        }
    }
    return @($roots)
}

function Get-CubismInstallations {
    param([string[]]$Roots = @())
    $seen = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $items = @()
    foreach ($root in @($Roots | Select-Object -First $script:CubismMaxRoots)) {
        $candidate = New-CubismInstallationCandidate -Root $root
        if ($null -ne $candidate.Key -and $seen.Add($candidate.Key)) { $items += $candidate }
    }
    return @($items | Sort-Object @{Expression={ if ($_.Version) { [version]$_.Version } else { [version]"0.0.0" } }}, @{Expression={ $_.CanonicalRoot.ToUpperInvariant() }})
}

function Merge-CubismSelection {
    param(
        [object[]]$Candidates = @(),
        [object[]]$SavedInstallations = @()
    )
    $saved = @{}
    foreach ($entry in @($SavedInstallations)) {
        $key = Get-CubismRootKey $entry.Root
        if ($null -ne $key -and -not $saved.ContainsKey($key)) { $saved[$key] = [bool]$entry.Selected }
    }
    foreach ($candidate in @($Candidates)) {
        if ($saved.ContainsKey($candidate.Key)) { $candidate.Selected = $saved[$candidate.Key] }
        else { $candidate.Selected = [bool]$candidate.Selectable }
    }
    return @($Candidates)
}

function Remove-CubismCandidateEntries {
    param(
        [object[]]$Candidates = @(),
        [string[]]$RemoveKeys = @(),
        [object[]]$StateInstallations = @(),
        [string[]]$ManualRoots = @(),
        [string[]]$AutomaticRootKeys = @()
    )
    $removeSet = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($key in @($RemoveKeys)) { if (-not [string]::IsNullOrWhiteSpace($key)) { [void]$removeSet.Add($key) } }
    $automaticSet = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($key in @($AutomaticRootKeys)) { if (-not [string]::IsNullOrWhiteSpace($key)) { [void]$automaticSet.Add($key) } }

    $remaining = [System.Collections.Generic.List[object]]::new()
    foreach ($candidate in @($Candidates)) {
        if ($removeSet.Contains($candidate.Key)) {
            if ($automaticSet.Contains($candidate.Key) -and $candidate.Selectable) {
                $candidate.Selected = $false
            }
            else { continue }
        }
        [void]$remaining.Add($candidate)
    }

    $state = @($StateInstallations | Where-Object { -not $removeSet.Contains((Get-CubismRootKey $_.Root)) })
    foreach ($candidate in @($remaining)) {
        $existing = @($state | Where-Object { (Get-CubismRootKey $_.Root) -eq $candidate.Key })
        if ($existing.Count -gt 0) { $existing[0].Selected = [bool]$candidate.Selected }
        elseif ($candidate.Selectable) {
            $state += [pscustomobject]@{
                Root = $candidate.CanonicalRoot; Version = $candidate.Version; Selected = [bool]$candidate.Selected
            }
        }
    }
    return [pscustomobject]@{
        Candidates = @($remaining)
        StateInstallations = @($state)
        ManualRoots = @($ManualRoots | Where-Object { -not $removeSet.Contains((Get-CubismRootKey $_)) })
    }
}

function Read-CubismStateBytes {
    param([string]$StatePath)
    $stream = [System.IO.File]::Open($StatePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $buffer = New-Object byte[] ($script:CubismMaxStateBytes + 1)
        $count = 0
        while ($count -lt $buffer.Length) {
            $read = $stream.Read($buffer, $count, $buffer.Length - $count)
            if ($read -eq 0) { break }
            $count += $read
        }
        if ($count -gt $script:CubismMaxStateBytes) { throw "state file is too large" }
        $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
        return $utf8.GetString($buffer, 0, $count)
    }
    finally { $stream.Dispose() }
}
function Get-CubismInstallationHome {
    param([string]$StatePath)
    $parent = Split-Path -Parent $StatePath
    if ((Split-Path -Leaf $parent) -ieq "config") { return (Split-Path -Parent $parent) }
    return $parent
}

function Get-CubismShortcutDirectory {
    param([string]$Override = "")
    if (-not [string]::IsNullOrWhiteSpace($Override)) {
        return [System.IO.Path]::GetFullPath($Override).TrimEnd('\', '/')
    }
    $programs = [Environment]::GetFolderPath("Programs")
    if ([string]::IsNullOrWhiteSpace($programs)) {
        throw "Cannot resolve the current-user Start Menu Programs directory"
    }
    return [System.IO.Path]::GetFullPath((Join-Path $programs "Turboism")).TrimEnd('\', '/')
}

function Test-CubismManagedShortcutPath {
    param([string]$Path, [string]$Directory = "")
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    try {
        $directoryPath = [System.IO.Path]::GetFullPath((Get-CubismShortcutDirectory -Override $Directory)).TrimEnd('\', '/')
        $full = [System.IO.Path]::GetFullPath($Path)
        $prefix = $directoryPath + [System.IO.Path]::DirectorySeparatorChar
        $name = [System.IO.Path]::GetFileName($full)
        $nameOk = $name -match '(?i)^Turboism Cubism 5\.[23](?:\.\d+)? \[[\p{L}\p{Nd}._ \-]{1,48}-[0-9A-F]{12}\](?: - D3D)?\.lnk$'
        return $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) -and
            [System.IO.Path]::GetDirectoryName($full).TrimEnd('\', '/') -ieq $directoryPath -and $nameOk
    }
    catch { return $false }
}

function Read-CubismInstallationState {
    param([string]$StatePath)
    $empty = [pscustomobject]@{
        Exists = $false; Valid = $true; Installations = @(); ManagedShortcuts = @()
        ManagedShortcutHashes = @(); ShortcutTakeovers = @(); LaunchMode = "independent"; Error = ""
    }
    try { $item = Get-Item -LiteralPath $StatePath -Force -ErrorAction Stop }
    catch [System.Management.Automation.ItemNotFoundException] { return $empty }
    catch {
        $empty.Exists = $true; $empty.Valid = $false; $empty.Error = $_.Exception.Message; return $empty
    }
    $empty.Exists = $true
    try {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "state file is a reparse point" }
        if ($item.PSIsContainer -or -not [System.IO.File]::Exists($StatePath)) { throw "state file is not regular" }
        $doc = Read-CubismStateBytes $StatePath | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $doc) { throw "state JSON is empty" }
        $stateKeys = @($doc.PSObject.Properties.Name)
        $requiredKeys = @("format", "schemaVersion", "installations", "managedShortcuts")
        $allowedKeys = @($requiredKeys + "launchMode" + "shortcutTakeovers" + "managedShortcutHashes")
        if (@($requiredKeys | Where-Object { $stateKeys -notcontains $_ }).Count -gt 0 -or
            @($stateKeys | Where-Object { $allowedKeys -notcontains $_ }).Count -gt 0) {
            throw "unknown or missing state fields"
        }
        if ($doc.format -ne $script:CubismStateFormat) { throw "unsupported state format" }
        if (($doc.schemaVersion -isnot [int]) -and ($doc.schemaVersion -isnot [long])) { throw "unsupported state schema" }
        if ([int64]$doc.schemaVersion -ne $script:CubismStateSchemaVersion) { throw "unsupported state schema" }
        $mode = "independent"
        if ($stateKeys -contains "launchMode") {
            if ($doc.launchMode -isnot [string] -or @("independent", "takeover") -notcontains $doc.launchMode) { throw "invalid launch mode" }
            $mode = [string]$doc.launchMode
        }
        if ($doc.installations -isnot [array] -or $doc.installations.Count -gt $script:CubismMaxStateEntries) { throw "invalid installation entries" }
        if ($doc.managedShortcuts -isnot [array] -or $doc.managedShortcuts.Count -gt $script:CubismMaxShortcutEntries) { throw "invalid shortcut entries" }

        $installations = @()
        $rootKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in @($doc.installations)) {
            $entryKeys = if ($null -eq $entry) { @() } else { @($entry.PSObject.Properties.Name) }
            $requiredEntryKeys = @("root", "version", "selected")
            if ($null -eq $entry -or $entryKeys.Count -ne $requiredEntryKeys.Count -or
                @($requiredEntryKeys | Where-Object { $entryKeys -notcontains $_ }).Count -gt 0 -or
                $entry.root -isnot [string] -or $entry.version -isnot [string] -or $entry.selected -isnot [bool]) {
                throw "invalid installation entry shape"
            }
            if ($entry.root.Length -eq 0 -or $entry.root.Length -gt $script:CubismMaxStateFieldLength -or $entry.version.Length -gt 16) {
                throw "invalid installation entry bounds"
            }
            $canonical = ConvertTo-CubismCanonicalRoot $entry.root
            $key = Get-CubismRootKey $canonical
            if ($null -eq $key -or -not $rootKeys.Add($key)) { throw "duplicate installation root" }
            $installations += [pscustomobject]@{ Root = $canonical; Version = $entry.version; Selected = [bool]$entry.selected }
        }

        $shortcuts = @()
        $shortcutKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($shortcut in @($doc.managedShortcuts)) {
            if ($shortcut -isnot [string] -or $shortcut.Length -eq 0 -or $shortcut.Length -gt $script:CubismMaxStateFieldLength -or
                -not (Test-CubismManagedShortcutPath $shortcut)) { throw "invalid managed shortcut path" }
            $key = [System.IO.Path]::GetFullPath($shortcut).ToUpperInvariant()
            if (-not $shortcutKeys.Add($key)) { throw "duplicate managed shortcut path" }
            $shortcuts += $shortcut
        }

        $hashes = @()
        if ($stateKeys -contains "managedShortcutHashes") {
            if ($null -eq $doc.managedShortcutHashes -or @($doc.managedShortcutHashes).Count -gt $script:CubismMaxShortcutEntries) { throw "invalid managed shortcut hashes" }
            foreach ($record in @($doc.managedShortcutHashes)) {
                $recordKeys = if ($null -eq $record) { @() } else { @($record.PSObject.Properties.Name) }
                if ($null -eq $record -or $recordKeys.Count -ne 2 -or $recordKeys -notcontains "path" -or $recordKeys -notcontains "sha256" -or
                    $record.path -isnot [string] -or $record.sha256 -isnot [string] -or $record.path.Length -eq 0 -or
                    $record.path.Length -gt $script:CubismMaxStateFieldLength -or $record.sha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
                    -not (Test-CubismManagedShortcutPath $record.path)) { throw "invalid managed shortcut hash" }
                $key = [System.IO.Path]::GetFullPath($record.path).ToUpperInvariant()
                if (-not $shortcutKeys.Contains($key)) { throw "managed shortcut hash has no owned path" }
                $hashes += [pscustomobject]@{ Path = $record.path; Sha256 = $record.sha256.ToUpperInvariant() }
            }
        }

        $takeovers = @()
        if ($stateKeys -contains "shortcutTakeovers") {
            if ($null -eq $doc.shortcutTakeovers -or @($doc.shortcutTakeovers).Count -gt 128) { throw "invalid shortcut takeovers" }
            foreach ($record in @($doc.shortcutTakeovers)) {
                $recordKeys = if ($null -eq $record) { @() } else { @($record.PSObject.Properties.Name) }
                $expected = @("shortcutPath", "backupPath", "originalSha256", "managedSha256", "root", "variant", "status")
                if ($null -eq $record -or $recordKeys.Count -ne $expected.Count -or
                    @($expected | Where-Object { $recordKeys -notcontains $_ }).Count -gt 0 -or
                    $record.shortcutPath -isnot [string] -or $record.backupPath -isnot [string] -or
                    $record.originalSha256 -isnot [string] -or $record.managedSha256 -isnot [string] -or
                    $record.root -isnot [string] -or $record.variant -isnot [string] -or $record.status -isnot [string]) {
                    throw "invalid shortcut takeover record"
                }
                if ($record.shortcutPath.Length -eq 0 -or $record.shortcutPath.Length -gt $script:CubismMaxStateFieldLength -or
                    $record.backupPath.Length -eq 0 -or $record.backupPath.Length -gt $script:CubismMaxStateFieldLength -or
                    $record.root.Length -eq 0 -or $record.root.Length -gt $script:CubismMaxStateFieldLength -or
                    @("normal", "d3d") -notcontains $record.variant -or @("pending", "active", "conflict") -notcontains $record.status -or
                    $record.originalSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
                    ($record.status -eq "active" -and $record.managedSha256 -notmatch '^[0-9A-Fa-f]{64}$') -or
                    ($record.status -ne "active" -and $record.managedSha256.Length -gt 0 -and $record.managedSha256 -notmatch '^[0-9A-Fa-f]{64}$')) {
                    throw "invalid shortcut takeover record bounds"
                }
                $stateHome = Get-CubismInstallationHome $StatePath
                if (-not (Test-CubismTakeoverShortcutPath $record.shortcutPath) -or
                    -not (Test-CubismConfinedBackupPath -TurboismHome $stateHome -RelativePath $record.backupPath)) {
                    throw "shortcut takeover path is outside an allowed boundary"
                }
                $takeovers += [pscustomobject]@{
                    ShortcutPath = [System.IO.Path]::GetFullPath($record.shortcutPath)
                    BackupPath = $record.backupPath
                    OriginalSha256 = $record.originalSha256.ToUpperInvariant()
                    ManagedSha256 = $record.managedSha256.ToUpperInvariant()
                    Root = $record.root; Variant = $record.variant; Status = $record.status
                }
            }
        }
        $empty.Installations = @($installations); $empty.ManagedShortcuts = @($shortcuts)
        $empty.ManagedShortcutHashes = @($hashes); $empty.ShortcutTakeovers = @($takeovers); $empty.LaunchMode = $mode
        return $empty
    }
    catch {
        $empty.Valid = $false; $empty.Installations = @(); $empty.ManagedShortcuts = @()
        $empty.ManagedShortcutHashes = @(); $empty.ShortcutTakeovers = @(); $empty.Error = $_.Exception.Message
        return $empty
    }
}

function Write-CubismInstallationState {
    param(
        [string]$StatePath,
        [object[]]$Candidates,
        [string[]]$ManagedShortcuts = @(),
        [object[]]$ManagedShortcutHashes = @(),
        [object[]]$ShortcutTakeovers = @(),
        [string]$LaunchMode = "independent"
    )
    if (@("independent", "takeover") -notcontains $LaunchMode) { throw "invalid launch mode" }
    $parent = Split-Path -Parent $StatePath
    $installationHome = Get-CubismInstallationHome $StatePath
    if (-not (Test-CubismNormalDirectory $parent)) { throw "Turboism home does not exist: $parent" }
    if (@($Candidates).Count -gt $script:CubismMaxStateEntries) { throw "too many installation entries" }
    $rootKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $entries = @($Candidates | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace($_.CanonicalRoot) -or $_.CanonicalRoot.Length -gt $script:CubismMaxStateFieldLength) { throw "invalid installation root" }
        $rootKey = Get-CubismRootKey $_.CanonicalRoot
        if ($null -eq $rootKey -or -not $rootKeys.Add($rootKey)) { throw "duplicate installation root" }
        if ([string]$_.Version -and $_.Version.Length -gt 16) { throw "invalid installation version" }
        [ordered]@{ root = $_.CanonicalRoot; version = [string]$_.Version; selected = [bool]$_.Selected }
    })
    $owned = @($ManagedShortcuts)
    if ($owned.Count -gt $script:CubismMaxShortcutEntries) { throw "too many managed shortcuts" }
    $shortcutKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($shortcut in $owned) {
        if ([string]::IsNullOrWhiteSpace($shortcut) -or $shortcut.Length -gt $script:CubismMaxStateFieldLength -or
            -not (Test-CubismManagedShortcutPath $shortcut)) { throw "invalid managed shortcut path" }
        if (-not $shortcutKeys.Add([System.IO.Path]::GetFullPath($shortcut).ToUpperInvariant())) { throw "duplicate managed shortcut" }
    }
    $hashEntries = @()
    foreach ($record in @($ManagedShortcutHashes)) {
        if ($null -eq $record -or $record.Path -isnot [string] -or $record.Sha256 -isnot [string] -or
            -not $shortcutKeys.Contains([System.IO.Path]::GetFullPath($record.Path).ToUpperInvariant()) -or
            $record.Sha256 -notmatch '^[0-9A-Fa-f]{64}$') { throw "invalid managed shortcut hash" }
        $hashEntries += [ordered]@{ path = $record.Path; sha256 = $record.Sha256.ToUpperInvariant() }
    }
    if ($hashEntries.Count -gt $script:CubismMaxShortcutEntries) { throw "too many managed shortcut hashes" }
    if (@($ShortcutTakeovers).Count -gt 128) { throw "too many shortcut takeovers" }
    $takeoverEntries = @()
    foreach ($record in @($ShortcutTakeovers)) {
        if ($null -eq $record -or $record.ShortcutPath -isnot [string] -or $record.BackupPath -isnot [string] -or
            $record.OriginalSha256 -isnot [string] -or $record.ManagedSha256 -isnot [string] -or $record.Root -isnot [string] -or
            $record.Variant -isnot [string] -or $record.Status -isnot [string] -or
            -not (Test-CubismTakeoverShortcutPath $record.ShortcutPath) -or
            -not (Test-CubismConfinedBackupPath -TurboismHome $installationHome -RelativePath $record.BackupPath) -or
            $record.OriginalSha256 -notmatch '^[0-9A-Fa-f]{64}$' -or
            ($record.Status -eq "active" -and $record.ManagedSha256 -notmatch '^[0-9A-Fa-f]{64}$') -or
            ($record.Status -ne "active" -and $record.ManagedSha256.Length -gt 0 -and $record.ManagedSha256 -notmatch '^[0-9A-Fa-f]{64}$') -or
            @("normal", "d3d") -notcontains $record.Variant -or @("pending", "active", "conflict") -notcontains $record.Status) {
            throw "invalid shortcut takeover record"
        }
        $takeoverEntries += [ordered]@{
            shortcutPath = $record.ShortcutPath; backupPath = $record.BackupPath; originalSha256 = $record.OriginalSha256.ToUpperInvariant()
            managedSha256 = $record.ManagedSha256.ToUpperInvariant(); root = $record.Root; variant = $record.Variant; status = $record.Status
        }
    }
    $doc = [ordered]@{
        format = $script:CubismStateFormat; schemaVersion = $script:CubismStateSchemaVersion
        installations = $entries; managedShortcuts = @($owned); launchMode = $LaunchMode
    }
    if ($takeoverEntries.Count -gt 0) { $doc.shortcutTakeovers = $takeoverEntries }
    if ($hashEntries.Count -gt 0) { $doc.managedShortcutHashes = $hashEntries }
    $text = $doc | ConvertTo-Json -Depth 8 -Compress
    $encoding = New-Object System.Text.UTF8Encoding($false)
    if ($encoding.GetByteCount($text) -gt $script:CubismMaxStateBytes) { throw "state JSON exceeds $($script:CubismMaxStateBytes) bytes" }
    $temporary = "$StatePath.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [System.IO.File]::WriteAllText($temporary, $text, $encoding)
        if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
            $target = Get-Item -LiteralPath $StatePath -Force -ErrorAction Stop
            if (($target.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "state file is a reparse point" }
            Invoke-CubismAtomicFileReplace -Source $temporary -Destination $StatePath
        }
        else { [System.IO.File]::Move($temporary, $StatePath) }
    }
    finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
    }
}

function Invoke-CubismAtomicFileReplace {
    param([string]$Source, [string]$Destination)
    # Same-volume atomic replacement with an explicit replacement-backup path,
    # required on PowerShell hosts where a $null backup is rejected.
    $directory = Split-Path -Parent $Destination
    $backupName = [System.IO.Path]::GetFileName($Destination) + ".$PID." + [guid]::NewGuid().ToString('N') + ".replacement-backup"
    $backup = Join-Path $directory $backupName
    $succeeded = $false
    try {
        [System.IO.File]::Replace($Source, $Destination, $backup, $true)
        $succeeded = $true
    }
    finally {
        # The backup is deleted only after a successful replace; a thrown or
        # incomplete replace leaves any created backup as recovery bytes.
        if ($succeeded -and (Test-Path -LiteralPath $backup -PathType Leaf)) {
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-CubismSha256 {
    param([string]$Path)
    if (-not (Test-CubismNormalFile $Path)) { throw "file is not a normal file: $Path" }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try { return ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace("-", "").ToUpperInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}

function Get-CubismTextSha256 {
    param([string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text)))).Replace("-", "").ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Test-CubismNormalFile {
    param([string]$Path)
    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        return (-not $item.PSIsContainer -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 -and [System.IO.File]::Exists($item.FullName))
    }
    catch { return $false }
}

function Test-CubismNormalDirectory {
    param([string]$Path)
    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        return ($item.PSIsContainer -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0)
    }
    catch { return $false }
}

function Get-CubismCurrentUserShortcutRoots {
    $roots = New-Object System.Collections.Generic.List[string]
    $keys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($value in @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))) {
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        $canonical = ConvertTo-CubismCanonicalRoot $value
        if ($null -ne $canonical -and (Test-CubismNormalDirectory $canonical) -and $keys.Add($canonical)) { [void]$roots.Add($canonical) }
    }
    if ($roots.Count -eq 0 -and -not [string]::IsNullOrWhiteSpace($env:APPDATA)) {
        $fallback = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"
        $canonical = ConvertTo-CubismCanonicalRoot $fallback
        if ($null -ne $canonical -and (Test-CubismNormalDirectory $canonical) -and $keys.Add($canonical)) { [void]$roots.Add($canonical) }
    }
    return @($roots)
}

function Test-CubismPathUnderRoots {
    param([string]$Path, [string[]]$Roots)
    try {
        $full = [System.IO.Path]::GetFullPath($Path)
        foreach ($root in @($Roots)) {
            $canonical = ConvertTo-CubismCanonicalRoot $root
            if ($null -eq $canonical) { continue }
            $prefix = $canonical.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
            if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
            $cursor = [System.IO.Path]::GetDirectoryName($full)
            $valid = $true
            while ($null -ne $cursor -and $cursor.Length -ge $canonical.Length) {
                if (-not (Test-CubismNormalDirectory $cursor)) { $valid = $false; break }
                if ($cursor.TrimEnd('\', '/') -ieq $canonical.TrimEnd('\', '/')) { break }
                $cursor = [System.IO.Path]::GetDirectoryName($cursor)
            }
            if ($valid) { return $true }
        }
    }
    catch { }
    return $false
}

function Test-CubismTakeoverShortcutPath {
    param([string]$Path, [string[]]$Roots = @())
    if ([string]::IsNullOrWhiteSpace($Path) -or [System.IO.Path]::GetExtension($Path) -ine ".lnk") { return $false }
    if ($Roots.Count -eq 0) { $Roots = @(Get-CubismCurrentUserShortcutRoots) }
    return Test-CubismPathUnderRoots -Path $Path -Roots $Roots
}

function Get-CubismShortcutFiles {
    param(
        [string[]]$Roots = @(),
        [int]$MaxEntries = $script:CubismMaxScanEntries,
        [int]$MaxResults = $script:CubismMaxScanResults
    )
    $queue = New-Object System.Collections.Generic.Queue[object]
    $seen = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $result = New-Object System.Collections.Generic.List[string]
    foreach ($root in @($Roots)) {
        $canonical = ConvertTo-CubismCanonicalRoot $root
        if ($null -ne $canonical -and (Test-CubismNormalDirectory $canonical) -and $seen.Add($canonical)) { $queue.Enqueue([pscustomobject]@{ Path = $canonical; Depth = 0 }) }
    }
    $inspected = 0
    while ($queue.Count -gt 0 -and $inspected -lt $MaxEntries -and $result.Count -lt $MaxResults) {
        $node = $queue.Dequeue()
        foreach ($item in @(Get-ChildItem -LiteralPath $node.Path -Force -ErrorAction SilentlyContinue)) {
            $inspected++
            if ($inspected -gt $MaxEntries) { break }
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
            if ($item.PSIsContainer) {
                if ($node.Depth -lt 8 -and $seen.Add($item.FullName)) { $queue.Enqueue([pscustomobject]@{ Path = $item.FullName; Depth = $node.Depth + 1 }) }
            }
            elseif ($item.Extension -ieq ".lnk" -and (Test-CubismNormalFile $item.FullName)) { [void]$result.Add($item.FullName) }
            if ($result.Count -ge $MaxResults) { break }
        }
    }
    return @($result)
}

function Get-CubismShortcutTarget {
    param([string]$Path)
    if (-not (Test-CubismNormalFile $Path)) { throw "shortcut is not a normal file" }
    $shell = $null; $shortcut = $null
    try {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($Path)
        if ([string]::IsNullOrWhiteSpace($shortcut.TargetPath)) { return $null }
        return [System.IO.Path]::GetFullPath($shortcut.TargetPath)
    }
    finally {
        if ($null -ne $shortcut) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shortcut) }
        if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    }
}

function Test-CubismConfinedBackupPath {
    param([string]$TurboismHome, [string]$RelativePath)
    # R12: only the exact relative name installer/shortcut-backups/<64-hex>.lnk
    # (either normal slash spelling) is admitted. Absolute paths, traversal,
    # nesting, alternate names, and alternate locations below home are rejected.
    if ([string]::IsNullOrWhiteSpace($TurboismHome) -or [string]::IsNullOrWhiteSpace($RelativePath)) { return $false }
    try {
        if ([System.IO.Path]::IsPathRooted($RelativePath)) { return $false }
        $parts = $RelativePath.Replace('/', '\').Split([char]'\')
        if ($parts.Count -ne 3) { return $false }
        if ($parts[0] -ine "installer" -or $parts[1] -ine "shortcut-backups") { return $false }
        if ($parts[2] -notmatch '^[0-9A-Fa-f]{64}\.lnk$') { return $false }
        $homeFull = [System.IO.Path]::GetFullPath($TurboismHome).TrimEnd('\', '/')
        $full = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($homeFull, $parts[0], $parts[1], $parts[2]))
        $prefix = $homeFull + [System.IO.Path]::DirectorySeparatorChar
        return $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    }
    catch { return $false }
}

function Test-CubismBackupChain {
    param([string]$TurboismHome)
    # R12: the real Home -> installer -> shortcut-backups directory chain must be
    # normal non-reparse directories immediately before any backup use.
    try {
        $homeFull = [System.IO.Path]::GetFullPath($TurboismHome)
        foreach ($path in @(
            $homeFull,
            [System.IO.Path]::Combine($homeFull, "installer"),
            [System.IO.Path]::Combine($homeFull, "installer", "shortcut-backups")
        )) {
            if (-not (Test-CubismNormalDirectory $path)) { return $false }
        }
        return $true
    }
    catch { return $false }
}

function Get-CubismResolvedBackupPath {
    param([string]$TurboismHome, [string]$RelativePath)
    # R12: exact-name confinement plus the normal non-reparse directory chain
    # are revalidated immediately before a backup read/restore/delete.
    if (-not (Test-CubismConfinedBackupPath -TurboismHome $TurboismHome -RelativePath $RelativePath)) { throw "shortcut backup path is outside the confined backup directory: $RelativePath" }
    if (-not (Test-CubismBackupChain $TurboismHome)) { throw "shortcut backup directory chain is not a normal directory chain: $TurboismHome" }
    $homeFull = [System.IO.Path]::GetFullPath($TurboismHome)
    $parts = $RelativePath.Replace('/', '\').Split([char]'\')
    return [System.IO.Path]::Combine($homeFull, $parts[0], $parts[1], $parts[2])
}

function Get-CubismBackupDirectory {
    param([string]$TurboismHome)
    if (-not (Test-CubismNormalDirectory $TurboismHome)) { throw "Turboism home is not a normal directory" }
    $installer = Join-Path $TurboismHome "installer"
    $directory = Join-Path $installer "shortcut-backups"
    foreach ($path in @($installer, $directory)) {
        if (Test-Path -LiteralPath $path) {
            if (-not (Test-CubismNormalDirectory $path)) { throw "shortcut backup directory is not a normal directory" }
        }
        else { New-Item -ItemType Directory -Path $path -Force | Out-Null }
    }
    return $directory
}

function Get-CubismBackupPathForShortcut {
    param([string]$TurboismHome, [string]$ShortcutPath)
    $name = (Get-CubismTextSha256 ([System.IO.Path]::GetFullPath($ShortcutPath))) + ".lnk"
    return [pscustomobject]@{ Relative = "installer\shortcut-backups\$name"; Full = (Join-Path (Get-CubismBackupDirectory $TurboismHome) $name) }
}

function Get-CubismBackupBytes {
    param([string]$TurboismHome)
    if (-not (Test-CubismBackupChain $TurboismHome)) { throw "shortcut backup directory chain is not a normal directory chain: $TurboismHome" }
    $directory = Join-Path (Join-Path $TurboismHome "installer") "shortcut-backups"
    $bytes = 0L
    foreach ($item in @(Get-ChildItem -LiteralPath $directory -Filter *.lnk -File -Force -ErrorAction SilentlyContinue)) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "shortcut backup is a reparse point" }
        $bytes += [int64]$item.Length
    }
    return $bytes
}

function New-CubismManagedShortcutStaged {
    param([string]$TurboismHome, [object]$Candidate, [string]$Variant, [string]$Path)
    $directory = Split-Path -Parent $Path
    if (-not (Test-CubismNormalDirectory $directory)) { throw "shortcut directory is not a normal directory" }
    $existing = $null
    try { $existing = Get-Item -LiteralPath $Path -Force -ErrorAction Stop } catch [System.Management.Automation.ItemNotFoundException] { }
    if ($null -ne $existing -and (($existing.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or $existing.PSIsContainer)) { throw "shortcut path is not a regular file" }
    $powershell = Join-Path $env:WINDIR "System32\WindowsPowerShell\v1.0\powershell.exe"
    $scriptPath = Join-Path $script:CubismScriptRoot "launch-cubism-turboism.ps1"
    if (-not (Test-CubismNormalFile $scriptPath)) { throw "managed launcher is missing" }
    $quote = { param([string]$value) '"' + $value.Replace('"', '\"') + '"' }
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File $(& $quote $scriptPath) -Home $(& $quote $TurboismHome) -CubismRoot $(& $quote $Candidate.CanonicalRoot)"
    if ($Variant -eq "d3d") { $arguments += " -Variant d3d" } else { $arguments += " -Variant normal" }
    $temporary = Join-Path $directory (".turboism-" + [guid]::NewGuid().ToString("N") + ".lnk")
    $shell = $null; $shortcut = $null
    try {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($temporary)
        $shortcut.TargetPath = $powershell; $shortcut.Arguments = $arguments; $shortcut.WorkingDirectory = $TurboismHome
        $shortcut.Description = "Turboism managed Cubism $($Candidate.Version) launch"
        $shortcut.Save()
        if (-not (Test-CubismNormalFile $temporary)) { throw "managed shortcut publication did not create a regular file" }
        return [pscustomobject]@{ Temporary = $temporary; Hash = (Get-CubismSha256 $temporary) }
    }
    finally {
        if ($null -ne $shortcut) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shortcut) }
        if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    }
}

function Publish-CubismStagedShortcut {
    param([string]$Temporary, [string]$Path)
    if (-not (Test-CubismNormalFile $Temporary)) { throw "staged shortcut is not a normal file" }
    if (Test-Path -LiteralPath $Path) {
        if (-not (Test-CubismNormalFile $Path)) { throw "shortcut destination is not a normal file" }
        Invoke-CubismAtomicFileReplace -Source $Temporary -Destination $Path
    }
    else { [System.IO.File]::Move($Temporary, $Path) }
    if (-not (Test-CubismNormalFile $Path)) { throw "shortcut publication failed" }
    return (Get-CubismSha256 $Path)
}

function Get-CubismShortcutName {
    param([object]$Candidate, [string]$Variant = "normal")
    $hash = (Get-CubismTextSha256 $Candidate.CanonicalRoot.ToUpperInvariant()).Substring(0, 12)
    $identity = $Candidate.CanonicalRoot -replace '[:\\/]+', '_' -replace '[^\p{L}\p{Nd}._ -]', '_' -replace '\s+', '_'
    if ($identity.Length -gt 48) { $identity = $identity.Substring($identity.Length - 48) }
    $suffix = if ($Variant -eq "d3d") { " - D3D" } else { "" }
    return "Turboism Cubism $($Candidate.Version) [$identity-$hash]$suffix.lnk"
}

function Get-CubismShortcutPath {
    param([object]$Candidate, [string]$Variant = "normal", [string]$Directory = "")
    return Join-Path (Get-CubismShortcutDirectory -Override $Directory) (Get-CubismShortcutName $Candidate $Variant)
}

function New-CubismManagedShortcut {
    param(
        [string]$TurboismHome, [object]$Candidate, [string]$Variant = "normal", [string]$ShortcutDirectory = ""
    )
    $directory = Get-CubismShortcutDirectory -Override $ShortcutDirectory
    if (Test-Path -LiteralPath $directory) {
        if (-not (Test-CubismNormalDirectory $directory)) { throw "shortcut directory is not a normal directory" }
    }
    else { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $path = Get-CubismShortcutPath -Candidate $Candidate -Variant $Variant -Directory $directory
    $staged = $null
    try {
        $staged = New-CubismManagedShortcutStaged -TurboismHome $TurboismHome -Candidate $Candidate -Variant $Variant -Path $path
        [void](Publish-CubismStagedShortcut -Temporary $staged.Temporary -Path $path)
        return $path
    }
    finally {
        if ($null -ne $staged -and (Test-Path -LiteralPath $staged.Temporary -PathType Leaf)) { Remove-Item -LiteralPath $staged.Temporary -Force -ErrorAction SilentlyContinue }
    }
}

function Remove-CubismManagedShortcuts {
    param(
        [string[]]$Paths = @(), [string]$Directory = "", [object[]]$HashRecords = @()
    )
    $failed = New-Object System.Collections.Generic.List[string]
    $hashes = @{}
    foreach ($record in @($HashRecords)) { if ($null -ne $record -and $record.Path) { $hashes[[System.IO.Path]::GetFullPath($record.Path).ToUpperInvariant()] = [string]$record.Sha256 } }
    foreach ($path in @($Paths)) {
        try {
            if (-not (Test-CubismManagedShortcutPath $path -Directory $Directory)) { [void]$failed.Add($path); continue }
            $full = [System.IO.Path]::GetFullPath($path)
            if (Test-Path -LiteralPath $full) {
                if (-not (Test-CubismNormalFile $full)) { [void]$failed.Add($path); continue }
                $key = $full.ToUpperInvariant()
                if ($hashes.ContainsKey($key) -and (Get-CubismSha256 $full) -ine $hashes[$key]) { [void]$failed.Add($path); continue }
                Remove-Item -LiteralPath $full -Force -ErrorAction Stop
                if (Test-Path -LiteralPath $full) { [void]$failed.Add($path) }
            }
        }
        catch { [void]$failed.Add($path) }
    }
    return @($failed)
}

function Get-CubismTakeoverMatches {
    param([object[]]$Candidates = @(), [string[]]$ShortcutRoots = @())
    if ($ShortcutRoots.Count -eq 0) { $ShortcutRoots = @(Get-CubismCurrentUserShortcutRoots) }
    $targets = @{}
    foreach ($candidate in @($Candidates | Where-Object { $_.Selected -and $_.Selectable })) {
        if (Test-CubismNormalFile $candidate.OfficialBat) { $targets[[System.IO.Path]::GetFullPath($candidate.OfficialBat).ToUpperInvariant()] = [pscustomobject]@{ Candidate = $candidate; Variant = "normal" } }
        if (-not [string]::IsNullOrWhiteSpace($candidate.D3DBat) -and (Test-CubismNormalFile $candidate.D3DBat)) { $targets[[System.IO.Path]::GetFullPath($candidate.D3DBat).ToUpperInvariant()] = [pscustomobject]@{ Candidate = $candidate; Variant = "d3d" } }
    }
    $matches = [System.Collections.Generic.List[object]]::new()
    foreach ($shortcut in @(Get-CubismShortcutFiles -Roots $ShortcutRoots)) {
        try {
            $target = Get-CubismShortcutTarget $shortcut
            if ([string]::IsNullOrWhiteSpace($target)) { continue }
            $key = [System.IO.Path]::GetFullPath($target).ToUpperInvariant()
            if ($targets.ContainsKey($key)) {
                $match = $targets[$key]
                [void]$matches.Add([pscustomobject]@{ ShortcutPath = $shortcut; Candidate = $match.Candidate; Variant = $match.Variant; Target = $target })
            }
        }
        catch { }
    }
    return @($matches)
}

function Get-CubismTakeoverPreview {
    param([object[]]$Candidates = @(), [object]$State = $null, [string[]]$ShortcutRoots = @())
    $matches = @(Get-CubismTakeoverMatches -Candidates $Candidates -ShortcutRoots $ShortcutRoots)
    $variants = [System.Collections.Generic.List[object]]::new()
    foreach ($candidate in @($Candidates | Where-Object { $_.Selected -and $_.Selectable })) {
        [void]$variants.Add([pscustomobject]@{ Candidate = $candidate; Variant = "normal" })
        if (-not [string]::IsNullOrWhiteSpace($candidate.D3DBat)) { [void]$variants.Add([pscustomobject]@{ Candidate = $candidate; Variant = "d3d" }) }
    }
    $unmatched = @($variants | Where-Object { $v = $_; @($matches | Where-Object { $_.Candidate.Key -eq $v.Candidate.Key -and $_.Variant -eq $v.Variant }).Count -eq 0 })
    $conflicts = [System.Collections.Generic.List[object]]::new()
    if ($null -ne $State -and $State.Valid) {
        foreach ($record in @($State.ShortcutTakeovers)) {
            try {
                if (-not (Test-CubismNormalFile $record.ShortcutPath)) { continue }
                $hash = Get-CubismSha256 $record.ShortcutPath
                if ($hash -ine $record.ManagedSha256 -and $hash -ine $record.OriginalSha256) { [void]$conflicts.Add($record) }
            }
            catch { [void]$conflicts.Add($record) }
        }
    }
    return [pscustomobject]@{ Eligible = @($matches); Unmatched = @($unmatched); Conflicted = @($conflicts) }
}

function Restore-CubismTakeoverRecords {
    param([string]$TurboismHome, [object[]]$Records = @())
    $plans = [System.Collections.Generic.List[object]]::new()
    foreach ($record in @($Records)) {
        $backup = Get-CubismResolvedBackupPath -TurboismHome $TurboismHome -RelativePath $record.BackupPath
        $currentExists = Test-Path -LiteralPath $record.ShortcutPath
        if ($currentExists -and -not (Test-CubismNormalFile $record.ShortcutPath)) { throw "shortcut takeover target is not a normal file: $($record.ShortcutPath)" }
        $currentHash = if ($currentExists) { Get-CubismSha256 $record.ShortcutPath } else { $null }
        if ($null -eq $currentHash -or $currentHash -eq $record.ManagedSha256) {
            if (-not (Test-CubismNormalFile $backup)) { throw "shortcut takeover backup is missing: $backup" }
            if ((Get-CubismSha256 $backup) -ine $record.OriginalSha256) { throw "shortcut takeover backup hash conflict: $backup" }
            [void]$plans.Add([pscustomobject]@{ Record = $record; Restore = $true })
        }
        elseif ($currentHash -eq $record.OriginalSha256) {
            if ((Test-Path -LiteralPath $backup -PathType Leaf) -and ((Get-CubismSha256 $backup) -ine $record.OriginalSha256)) { throw "shortcut takeover backup hash conflict: $backup" }
            [void]$plans.Add([pscustomobject]@{ Record = $record; Restore = $false })
        }
        else { throw "shortcut takeover conflict: $($record.ShortcutPath)" }
    }
    foreach ($plan in @($plans | Where-Object { $_.Restore })) {
        # R12: recompute the backup from the record identity and require a normal
        # file with the original hash immediately before copy/publish; the cached
        # preflight path is never copied.
        $backup = Get-CubismResolvedBackupPath -TurboismHome $TurboismHome -RelativePath $plan.Record.BackupPath
        if (-not (Test-CubismNormalFile $backup)) { throw "shortcut takeover backup is not a normal file: $backup" }
        if ((Get-CubismSha256 $backup) -ine $plan.Record.OriginalSha256) { throw "shortcut takeover backup hash conflict: $backup" }
        $directory = Split-Path -Parent $plan.Record.ShortcutPath
        if (-not (Test-CubismNormalDirectory $directory)) { throw "shortcut takeover parent is not a normal directory" }
        $temporary = Join-Path $directory (".turboism-restore-" + [guid]::NewGuid().ToString("N") + ".lnk")
        try {
            [System.IO.File]::Copy($backup, $temporary, $false)
            [void](Publish-CubismStagedShortcut -Temporary $temporary -Path $plan.Record.ShortcutPath)
            if ((Get-CubismSha256 $plan.Record.ShortcutPath) -ine $plan.Record.OriginalSha256) { throw "shortcut restoration hash mismatch" }
        }
        finally { if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue } }
    }
    return @($Records)
}

function Remove-CubismEmptyBackupDirectories {
    param([string]$TurboismHome)
    # Remove only the fixed, empty backup directory chain. Unknown files keep
    # either directory non-empty and are preserved; links/non-directories fail
    # the normal-directory check and are never removed.
    $installer = Join-Path $TurboismHome "installer"
    $backups = Join-Path $installer "shortcut-backups"
    foreach ($directory in @($backups, $installer)) {
        if (-not (Test-CubismNormalDirectory $directory)) { continue }
        $entries = @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop)
        if ($entries.Count -eq 0) {
            Remove-Item -LiteralPath $directory -Force -ErrorAction Stop
        }
    }
}

function Remove-CubismTakeoverBackups {
    param([string]$TurboismHome, [object[]]$Records = @())
    foreach ($record in @($Records)) {
        $backup = Get-CubismResolvedBackupPath -TurboismHome $TurboismHome -RelativePath $record.BackupPath
        if (Test-Path -LiteralPath $backup) {
            if (-not (Test-CubismNormalFile $backup)) { throw "shortcut backup is not a normal file" }
            if ((Get-CubismSha256 $backup) -ine $record.OriginalSha256) { throw "shortcut backup hash conflict: $backup" }
            Remove-Item -LiteralPath $backup -Force -ErrorAction Stop
        }
    }
    Remove-CubismEmptyBackupDirectories -TurboismHome $TurboismHome
}

function Invoke-CubismLaunchConfiguration {
    param(
        [string]$TurboismHome, [string]$StatePath, [object[]]$Candidates, [string]$LaunchMode = "independent",
        [object]$ExistingState = $null, [string]$ShortcutDirectory = ""
    )
    if (@("independent", "takeover") -notcontains $LaunchMode) { throw "invalid launch mode" }
    if ($null -eq $ExistingState) { $ExistingState = Read-CubismInstallationState $StatePath }
    if ($ExistingState.Exists -and -not $ExistingState.Valid) { throw "managed Cubism state is invalid: $($ExistingState.Error)" }
    $oldRecords = @()
    $oldShortcuts = @()
    $oldHashes = @()
    if ($ExistingState.Valid) {
        $oldRecords = @($ExistingState.ShortcutTakeovers)
        $oldShortcuts = @($ExistingState.ManagedShortcuts)
        $oldHashes = @($ExistingState.ManagedShortcutHashes)
    }
    $selected = @($Candidates | Where-Object { $_.Selected -and $_.Selectable })
    $newShortcuts = New-Object System.Collections.Generic.List[string]
    $newHashes = [System.Collections.Generic.List[object]]::new()
    $created = New-Object System.Collections.Generic.List[string]
    try {
        if ($oldRecords.Count -gt 0) {
            [void](Restore-CubismTakeoverRecords -TurboismHome $TurboismHome -Records $oldRecords)
            Remove-CubismTakeoverBackups -TurboismHome $TurboismHome -Records $oldRecords
        }
        $failed = @(Remove-CubismManagedShortcuts -Paths $oldShortcuts -Directory $ShortcutDirectory -HashRecords $oldHashes)
        if ($failed.Count -gt 0) { throw "managed shortcut cleanup is incomplete: $($failed -join ', ')" }
        if ($LaunchMode -eq "independent") {
            foreach ($candidate in $selected) {
                foreach ($variant in @("normal", "d3d")) {
                    if ($variant -eq "d3d" -and [string]::IsNullOrWhiteSpace($candidate.D3DBat)) { continue }
                    $path = Get-CubismShortcutPath $candidate $variant $ShortcutDirectory
                    if ((Test-Path -LiteralPath $path) -and ($oldShortcuts -notcontains $path)) { throw "refusing to overwrite an unowned managed shortcut: $path" }
                    $createdPath = New-CubismManagedShortcut -TurboismHome $TurboismHome -Candidate $candidate -Variant $variant -ShortcutDirectory $ShortcutDirectory
                    [void]$newShortcuts.Add($createdPath); [void]$created.Add($createdPath)
                    [void]$newHashes.Add([pscustomobject]@{ Path = $createdPath; Sha256 = Get-CubismSha256 $createdPath })
                }
            }
            Write-CubismInstallationState -StatePath $StatePath -Candidates $Candidates -ManagedShortcuts @($newShortcuts) -ManagedShortcutHashes @($newHashes) -LaunchMode "independent"
            return [pscustomobject]@{ ManagedShortcuts = @($newShortcuts); ManagedShortcutHashes = @($newHashes); ShortcutTakeovers = @(); Eligible = @(); Unmatched = @(); Conflicted = @() }
        }

        $matches = @(Get-CubismTakeoverMatches -Candidates $Candidates)
        if ($matches.Count -gt 128) { throw "shortcut takeover record cap exceeded" }
        $records = [System.Collections.Generic.List[object]]::new()
        foreach ($match in $matches) {
            $original = $match.ShortcutPath
            if (-not (Test-CubismTakeoverShortcutPath $original)) { throw "shortcut takeover path is outside current-user roots" }
            $originalFile = Get-Item -LiteralPath $original -Force -ErrorAction Stop
            if ($originalFile.PSIsContainer -or
                ($originalFile.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
                [System.IO.Path]::GetExtension($originalFile.FullName) -ine ".lnk") {
                throw "shortcut takeover source is not a normal .lnk file"
            }
            $originalLength = [int64]$originalFile.Length
            $originalHash = Get-CubismSha256 $original
            $backupInfo = Get-CubismBackupPathForShortcut -TurboismHome $TurboismHome -ShortcutPath $original
            $backup = $backupInfo.Full
            $newBackup = $false
            $pendingPublished = $false
            try {
                $backupBytes = Get-CubismBackupBytes -TurboismHome $TurboismHome
                if ($backupBytes -gt 8MB) { throw "shortcut backup byte cap exceeded" }
                if (Test-Path -LiteralPath $backup) {
                    $backupFile = Get-Item -LiteralPath $backup -Force -ErrorAction Stop
                    if (-not (Test-CubismNormalFile $backup) -or
                        [int64]$backupFile.Length -ne $originalLength -or
                        (Get-CubismSha256 $backup) -ine $originalHash) { throw "shortcut backup conflict: $backup" }
                }
                else {
                    if ($backupBytes + $originalLength -gt 8MB) { throw "shortcut backup byte cap exceeded" }
                    $newBackup = $true
                    [System.IO.File]::Copy($original, $backup, $false)
                    $backupFile = Get-Item -LiteralPath $backup -Force -ErrorAction Stop
                    if (-not (Test-CubismNormalFile $backup) -or
                        [int64]$backupFile.Length -ne $originalLength -or
                        (Get-CubismSha256 $backup) -ine $originalHash) { throw "shortcut backup verification failed: $backup" }
                }
                if ((Get-CubismBackupBytes -TurboismHome $TurboismHome) -gt 8MB) { throw "shortcut backup byte cap exceeded" }
                $path = Get-CubismShortcutPath $match.Candidate $match.Variant
                $staged = New-CubismManagedShortcutStaged -TurboismHome $TurboismHome -Candidate $match.Candidate -Variant $match.Variant -Path $original
                $pending = [pscustomobject]@{ ShortcutPath = $original; BackupPath = $backupInfo.Relative; OriginalSha256 = $originalHash; ManagedSha256 = $staged.Hash; Root = $match.Candidate.CanonicalRoot; Variant = $match.Variant; Status = "pending" }
                [void]$records.Add($pending)
                Write-CubismInstallationState -StatePath $StatePath -Candidates $Candidates -ManagedShortcuts @($newShortcuts) -ManagedShortcutHashes @($newHashes) -ShortcutTakeovers @($records) -LaunchMode "takeover"
                $pendingPublished = $true
                try { [void](Publish-CubismStagedShortcut -Temporary $staged.Temporary -Path $original) }
                finally { if (Test-Path -LiteralPath $staged.Temporary -PathType Leaf) { Remove-Item -LiteralPath $staged.Temporary -Force -ErrorAction SilentlyContinue } }
                $pending.Status = "active"
                $pending.ManagedSha256 = Get-CubismSha256 $original
                Write-CubismInstallationState -StatePath $StatePath -Candidates $Candidates -ManagedShortcuts @($newShortcuts) -ManagedShortcutHashes @($newHashes) -ShortcutTakeovers @($records) -LaunchMode "takeover"
            }
            catch {
                # A newly-created backup is removable only before the pending
                # record has been published; afterwards the evidence must survive.
                if ($newBackup -and -not $pendingPublished -and (Test-Path -LiteralPath $backup -PathType Leaf)) { Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue }
                throw
            }
        }
        $variants = [System.Collections.Generic.List[object]]::new()
        foreach ($candidate in $selected) {
            [void]$variants.Add([pscustomobject]@{ Candidate = $candidate; Variant = "normal" })
            if (-not [string]::IsNullOrWhiteSpace($candidate.D3DBat)) { [void]$variants.Add([pscustomobject]@{ Candidate = $candidate; Variant = "d3d" }) }
        }
        foreach ($variant in @($variants | Where-Object { $v = $_; @($matches | Where-Object { $_.Candidate.Key -eq $v.Candidate.Key -and $_.Variant -eq $v.Variant }).Count -eq 0 })) {
            $ownedPath = Get-CubismShortcutPath $variant.Candidate $variant.Variant $ShortcutDirectory
            if ((Test-Path -LiteralPath $ownedPath) -and ($oldShortcuts -notcontains $ownedPath)) { throw "refusing to overwrite an unowned managed shortcut: $ownedPath"
            }
            $path = New-CubismManagedShortcut -TurboismHome $TurboismHome -Candidate $variant.Candidate -Variant $variant.Variant -ShortcutDirectory $ShortcutDirectory
            [void]$newShortcuts.Add($path); [void]$created.Add($path)
            [void]$newHashes.Add([pscustomobject]@{ Path = $path; Sha256 = Get-CubismSha256 $path })
        }
        Write-CubismInstallationState -StatePath $StatePath -Candidates $Candidates -ManagedShortcuts @($newShortcuts) -ManagedShortcutHashes @($newHashes) -ShortcutTakeovers @($records) -LaunchMode "takeover"
        $preview = Get-CubismTakeoverPreview -Candidates $Candidates
        return [pscustomobject]@{ ManagedShortcuts = @($newShortcuts); ManagedShortcutHashes = @($newHashes); ShortcutTakeovers = @($records); Eligible = @($matches); Unmatched = @($preview.Unmatched); Conflicted = @() }
    }
    catch {
        if ($created.Count -gt 0) { [void](Remove-CubismManagedShortcuts -Paths @($created) -Directory $ShortcutDirectory -HashRecords @($newHashes)) }
        throw
    }
}

function Invoke-CubismManagedCleanup {
    param([string]$TurboismHome, [string]$StatePath, [string]$ShortcutDirectory = "")
    $state = Read-CubismInstallationState -StatePath $StatePath
    if (-not $state.Valid) { throw "Refusing shortcut cleanup because managed state is invalid: $($state.Error)" }
    $records = @($state.ShortcutTakeovers); $hashes = @($state.ManagedShortcutHashes)
    if ($records.Count -gt 0) { [void](Restore-CubismTakeoverRecords -TurboismHome $TurboismHome -Records $records) }
    $failed = @(Remove-CubismManagedShortcuts -Paths $state.ManagedShortcuts -Directory $ShortcutDirectory -HashRecords $hashes)
    if ($failed.Count -gt 0) { throw "Managed shortcut cleanup is incomplete; state was preserved for retry." }
    if ($records.Count -gt 0) { Remove-CubismTakeoverBackups -TurboismHome $TurboismHome -Records $records }
    if (Test-Path -LiteralPath $StatePath) { Remove-Item -LiteralPath $StatePath -Force -ErrorAction Stop }
}


function Read-TurboismRetiredPluginId {
    param([string]$JarPath)
    if (-not (Test-CubismNormalFile $JarPath)) { return $null }
    $zip = $null
    $entryStream = $null
    $memory = $null
    try {
        Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        $entry = $zip.GetEntry("META-INF/turboism/plugin.json")
        if ($null -eq $entry -or $entry.Length -lt 0 -or $entry.Length -gt 65536) { return $null }
        $entryStream = $entry.Open()
        $memory = New-Object System.IO.MemoryStream
        $buffer = New-Object byte[] 4096
        $total = 0
        while (($read = $entryStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $total += $read
            if ($total -gt 65536) { return $null }
            $memory.Write($buffer, 0, $read)
        }
        $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
        $document = $utf8.GetString($memory.ToArray()) | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $document -or $document.id -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string]$document.id)) { return $null }
        return [string]$document.id
    }
    catch { return $null }
    finally {
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $entryStream) { $entryStream.Dispose() }
        if ($null -ne $zip) { $zip.Dispose() }
    }
}

function Remove-TurboismRetiredPlugins {
    param([string]$TurboismHome)
    $plugins = Join-Path $TurboismHome "plugins"
    if (-not (Test-Path -LiteralPath $plugins)) { return }
    if (-not (Test-CubismNormalDirectory $plugins)) {
        Write-Host "Turboism: preserved plugin directory $plugins (not a normal directory)"
        return
    }
    $entries = @(Get-ChildItem -LiteralPath $plugins -File -Force -ErrorAction Stop)
    if ($entries.Count -gt 4096) { throw "plugin directory entry cap exceeded" }
    $retired = @(
        "dev.turboism.plugin.logfilter",
        "dev.turboism.plugin.clipmask",
        "dev.turboism.plugin.perfopt",
        "dev.turboism.plugin.renderopt"
    )
    foreach ($entry in $entries) {
        if ($entry.Extension -ine ".jar") { continue }
        $id = Read-TurboismRetiredPluginId -JarPath $entry.FullName
        if ($null -eq $id -or @($retired | Where-Object { $_ -ceq $id }).Count -eq 0) {
            continue
        }
        Remove-Item -LiteralPath $entry.FullName -Force -ErrorAction Stop
        Write-Host "Turboism: removed retired plugin $($entry.FullName) (id=$id)"
    }
}

function Get-JdkOptionTokens {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    if ($Text.Length -gt $script:CubismMaxJdkOptionText) { throw "JVM option text exceeds bound" }
    $tokens = New-Object System.Collections.Generic.List[string]
    $builder = New-Object System.Text.StringBuilder
    $quoted = $false
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $c = $Text[$i]
        if ($c -eq '"') {
            $quoted = -not $quoted
            [void]$builder.Append($c)
        }
        elseif ([char]::IsWhiteSpace($c) -and -not $quoted) {
            if ($builder.Length -gt 0) {
                if ($builder.Length -gt $script:CubismMaxJdkOptionLength) { throw "JVM option exceeds bound" }
                [void]$tokens.Add($builder.ToString())
                [void]$builder.Clear()
            }
        }
        else { [void]$builder.Append($c) }
    }
    if ($quoted) { throw "unsupported unmatched quote in JVM options" }
    if ($builder.Length -gt 0) {
        if ($builder.Length -gt $script:CubismMaxJdkOptionLength) { throw "JVM option exceeds bound" }
        [void]$tokens.Add($builder.ToString())
    }
    if ($tokens.Count -gt $script:CubismMaxJdkOptionTokens) { throw "too many JVM options" }
    return @($tokens)
}

function Remove-TurboismJdkOptions {
    param([string]$Text)
    $kept = New-Object System.Collections.Generic.List[string]
    foreach ($token in @(Get-JdkOptionTokens $Text)) {
        $value = $token.Trim()
        if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) { $value = $value.Substring(1, $value.Length - 2) }
        $probe = $value.Replace('"', '')
        if ($probe -match '(?i)^-Dturboism\.home=' -or
            $probe -match '(?i)^-Dturboism\.graal\.(?:enabled|java|classpath|mainClass|startupTimeoutMillis)=' -or
            $probe -match '(?i)^-javaagent:.*turboism-agent\.jar(?:[=].*)?$' -or
            $probe -match '(?i)^--add-exports=java\.base[./]jdk\.internal\.org\.objectweb\.asm(?:[.]commons)?=ALL-UNNAMED$') { continue }
        [void]$kept.Add($token)
    }
    return ($kept -join " ").Trim()
}

function Get-CubismManagedJdkExportTokens {
    # Legal JDK 17 --add-exports syntax is module/package; the legacy
    # malformed java.base.jdk... dot spelling is never emitted.
    return @(
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
    )
}

function ConvertTo-JdkOptionToken {
    param([string]$Option)
    if ([string]::IsNullOrWhiteSpace($Option) -or $Option.Length -gt $script:CubismMaxJdkOptionLength -or $Option -match '[\r\n]') { throw "invalid managed JVM option" }
    if ($Option -match '[\s"]') { return '"' + $Option.Replace('"', '\"') + '"' }
    return $Option
}

function Read-CubismJvmPreference {
    param([string]$TurboismHome)
    $path = Join-Path $TurboismHome "config.json"
    if (-not (Test-Path -LiteralPath $path)) { return "graalvm" }
    if (-not (Test-CubismNormalFile $path)) { throw "Turboism config is not a normal file" }
    try { $document = Read-CubismStateBytes $path | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "Turboism config is invalid or exceeds bound" }
    if ($null -eq $document.launcher -or $null -eq $document.launcher.cubismJvm) { return "graalvm" }
    if ($document.launcher.cubismJvm -isnot [string] -or
        @("graalvm", "bundled") -notcontains [string]$document.launcher.cubismJvm) {
        throw "Turboism Cubism JVM setting is invalid"
    }
    return [string]$document.launcher.cubismJvm
}

function Test-CubismCompatibleGraalJava {
    param([string]$JavaPath)
    if (-not (Test-CubismNormalFile $JavaPath)) { return $false }
    try {
        $bin = Split-Path -Parent ([System.IO.Path]::GetFullPath($JavaPath))
        $home = Split-Path -Parent $bin
        foreach ($path in @($home, $bin)) {
            if (-not (Test-CubismNormalDirectory $path)) { return $false }
        }
        $release = Join-Path $home "release"
        if (-not (Test-CubismNormalFile $release)) { return $false }
        $item = Get-Item -LiteralPath $release -Force -ErrorAction Stop
        if ($item.Length -gt 65536) { return $false }
        $metadata = Get-Content -LiteralPath $release -Raw -Encoding UTF8 -ErrorAction Stop
        return $metadata -match '(?m)^IMPLEMENTOR="GraalVM Community"\s*$' -and
            $metadata -match '(?m)^GRAALVM_VERSION="25\.2\.4"\s*$' -and
            $metadata -match '(?m)^JAVA_VERSION="25\.0\.4"\s*$'
    }
    catch { return $false }
}

function Test-CubismManagedGraalChain {
    param([string]$TurboismHome)
    try {
        $home = [System.IO.Path]::GetFullPath($TurboismHome)
        foreach ($path in @(
            $home,
            (Join-Path $home "graal"),
            (Join-Path $home "graal\runtime"),
            (Join-Path $home "graal\runtime\bin")
        )) {
            if (-not (Test-CubismNormalDirectory $path)) { return $false }
        }
        return $true
    }
    catch { return $false }
}

function Find-CubismGraalJava {
    param([string]$TurboismHome, [string]$ExplicitJava = "")
    if (-not [string]::IsNullOrWhiteSpace($ExplicitJava)) {
        $explicit = $ExplicitJava
        if (Test-Path -LiteralPath $explicit -PathType Container) {
            $explicit = Join-Path $explicit "bin\java.exe"
        }
        if (-not (Test-CubismNormalFile $explicit)) { return "" }
        return [System.IO.Path]::GetFullPath($explicit)
    }
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_CUBISM_JAVA)) { $candidates += $env:TURBOISM_CUBISM_JAVA }
    $candidates += (Join-Path $TurboismHome "graal\runtime\bin\java.exe")
    $candidates += (Join-Path $TurboismHome "graalvm\bin\java.exe")
    if (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_GRAALVM_HOME)) {
        $candidates += (Join-Path $env:TURBOISM_GRAALVM_HOME "bin\java.exe")
    }
    if (-not [string]::IsNullOrWhiteSpace($env:GRAALVM_HOME)) {
        $candidates += (Join-Path $env:GRAALVM_HOME "bin\java.exe")
    }
    $managedPath = [System.IO.Path]::GetFullPath((Join-Path $TurboismHome "graal\runtime\bin\java.exe"))
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $path = $candidate
        if (Test-Path -LiteralPath $path -PathType Container) { $path = Join-Path $path "bin\java.exe" }
        $full = [System.IO.Path]::GetFullPath($path)
        if ($full -ieq $managedPath -and -not (Test-CubismManagedGraalChain $TurboismHome)) { continue }
        if (-not (Test-CubismCompatibleGraalJava $full)) { continue }
        return $full
    }
    return ""
}

function Resolve-CubismGraalJava {
    param([string]$TurboismHome, [string]$ExplicitJava = "")
    $resolved = Find-CubismGraalJava -TurboismHome $TurboismHome -ExplicitJava $ExplicitJava
    if ([string]::IsNullOrWhiteSpace($resolved)) {
        throw "GraalVM is selected for Cubism, but no GraalVM java.exe is available"
    }
    return $resolved
}

function Resolve-TurboismGraalHost {
    param([string]$TurboismHome, [string]$PreferredJava = "")
    $java = $PreferredJava
    if (-not [string]::IsNullOrWhiteSpace($java)) {
        try { $java = [System.IO.Path]::GetFullPath($java) }
        catch { throw "Graal child-host Java path is invalid" }
        if (-not (Test-CubismNormalFile $java)) {
            throw "Graal child-host Java executable is unavailable: $java"
        }
    }
    else {
        try { $java = Resolve-CubismGraalJava -TurboismHome $TurboismHome }
        catch { return $null }
    }
    $libraryRoot = Join-Path $TurboismHome "graal\lib"
    if (-not (Test-CubismNormalDirectory $libraryRoot)) { return $null }
    $libraries = @(
        Get-ChildItem -LiteralPath $libraryRoot -File -Force -ErrorAction SilentlyContinue |
            Where-Object {
                ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 -and
                $_.Name -match '(?i)\.jar$'
            }
    )
    if ($libraries.Count -eq 0) { return $null }
    $requiredPatterns = @(
        "graal-host-*.jar",
        "jackson-annotations-*.jar",
        "jackson-core-*.jar",
        "jackson-databind-*.jar",
        "collections-*.jar",
        "jniutils-*.jar",
        "js-isolate-windows-amd64-community-*.jar",
        "nativebridge-*.jar",
        "nativeimage-*.jar",
        "polyglot-*.jar",
        "truffle-api-*.jar",
        "word-*.jar"
    )
    $missing = @()
    foreach ($pattern in $requiredPatterns) {
        if (@($libraries | Where-Object { $_.Name -like $pattern }).Count -eq 0) {
            $missing += $pattern
        }
    }
    if ($missing.Count -gt 0) {
        throw "Turboism Graal host library closure is incomplete: $($missing -join ', ')"
    }
    return [pscustomobject]@{
        Java = [System.IO.Path]::GetFullPath($java)
        ClassPath = (Join-Path $libraryRoot "*")
    }
}

function Get-CubismLaunchStageDirectory {
    param([string]$TurboismHome)
    if (-not (Test-CubismNormalDirectory $TurboismHome)) { throw "Turboism home is not a normal directory" }
    $state = Join-Path $TurboismHome "state"
    $directory = Join-Path $state "managed-launch"
    foreach ($path in @($state, $directory)) {
        if (Test-Path -LiteralPath $path) {
            if (-not (Test-CubismNormalDirectory $path)) { throw "managed launch staging directory is not a normal directory" }
        }
        else { New-Item -ItemType Directory -Path $path -Force | Out-Null }
    }
    return $directory
}

function New-CubismJavaOverrideBat {
    param(
        [string]$OfficialBat,
        [string]$CubismRoot,
        [string]$TurboismHome,
        [string]$JavaExecutable
    )
    $root = ConvertTo-CubismCanonicalRoot $CubismRoot
    $official = [System.IO.Path]::GetFullPath($OfficialBat)
    $java = [System.IO.Path]::GetFullPath($JavaExecutable)
    if ($null -eq $root -or -not (Test-CubismNormalFile $official) -or -not (Test-CubismNormalFile $java)) {
        throw "Cubism JVM override input is invalid"
    }
    $rootPrefix = $root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $official.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "official Cubism BAT is outside the selected root"
    }
    if ($root -match '[\r\n&|<>^%!`"]' -or $java -match '[\r\n&|<>^%!`"]') {
        throw "Cubism JVM override path contains an unsupported command character"
    }
    $directory = Get-CubismLaunchStageDirectory -TurboismHome $TurboismHome
    $name = ".turboism-java-$PID-$([guid]::NewGuid().ToString('N')).bat"
    $temporary = Join-Path $directory $name
    $encoding = [System.Text.Encoding]::Default
    $text = [System.IO.File]::ReadAllText($official, $encoding)
    $rootPattern = '(?im)^cd\s+/d\s+"%~dp0"\s*(?:\r?\n)'
    $rootMatches = [regex]::Matches($text, $rootPattern)
    if ($rootMatches.Count -ne 1) { throw "official Cubism BAT must contain exactly one root-relative working-directory assignment" }
    $javaPattern = '(?im)^set(?: ")?JAVA_EXE=.*(?:\r?\n)'
    $javaMatches = [regex]::Matches($text, $javaPattern)
    if ($javaMatches.Count -ne 1) { throw "official Cubism BAT must contain exactly one JAVA_EXE assignment" }
    $workingDirectory = 'cd /d "' + $root + '"' + "`r`n"
    $replacement = 'set "JAVA_EXE=' + $java + '"' + "`r`n"
    $staged = [regex]::Replace($text, $rootPattern, { param($match) $workingDirectory }, 1)
    $staged = [regex]::Replace($staged, $javaPattern, { param($match) $replacement }, 1)
    try {
        [System.IO.File]::WriteAllText($temporary, $staged, $encoding)
        if (-not (Test-CubismNormalFile $temporary)) { throw "Cubism JVM override BAT was not created" }
        return $temporary
    }
    catch {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
        throw
    }
}

function Invoke-CubismOfficialBat {
    param(
        [string]$OfficialBat,
        [string]$CubismRoot,
        [string]$TurboismHome,
        [string]$Agent,
        [object]$GraalHost = $null,
        [string[]]$Arguments = @()
    )
    if ([string]::IsNullOrWhiteSpace($TurboismHome) -or [string]::IsNullOrWhiteSpace($Agent) -or
        $TurboismHome.Length -gt $script:CubismMaxStateFieldLength -or $Agent.Length -gt $script:CubismMaxStateFieldLength) { throw "launch path exceeds bound" }
    if (@($Arguments).Count -gt $script:CubismMaxLaunchArguments -or @($Arguments | Where-Object { $_.Length -gt $script:CubismMaxStateFieldLength }).Count -gt 0) { throw "launch arguments exceed bound" }
    $root = ConvertTo-CubismCanonicalRoot $CubismRoot
    $official = $null
    $home = ConvertTo-CubismCanonicalRoot $TurboismHome
    if ($null -eq $home -or -not (Test-CubismNormalDirectory $home) -or -not (Test-CubismNormalFile $Agent)) {
        throw "Turboism launch home or agent is invalid"
    }
    try { $official = [System.IO.Path]::GetFullPath($OfficialBat) } catch { }
    if ($null -ne $GraalHost) {
        $graalJava = if ($null -eq $GraalHost.Java) { "" } else { [string]$GraalHost.Java }
        $graalClassPath = if ($null -eq $GraalHost.ClassPath) { "" } else { [string]$GraalHost.ClassPath }
        $expectedClassPath = Join-Path (Join-Path $home "graal") "lib\*"
        $graalJavaLength = if ($null -eq $graalJava) { 0 } else { $graalJava.Length }
        $graalClassPathLength = if ($null -eq $graalClassPath) { 0 } else { $graalClassPath.Length }
        if ($graalJavaLength -gt $script:CubismMaxStateFieldLength -or
            $graalClassPathLength -gt $script:CubismMaxStateFieldLength -or
            -not (Test-CubismNormalFile $graalJava) -or
            $graalClassPath -ine $expectedClassPath) {
            throw "Graal child-host configuration is invalid"
        }
    }
    $officialDirectory = if ($null -eq $official) { "" } else { [System.IO.Path]::GetDirectoryName($official).TrimEnd('\', '/') }
    $officialName = if ($null -eq $official) { "" } else { [System.IO.Path]::GetFileName($official) }
    $rootEntry = $officialDirectory -ieq $root -and $officialName -match '(?i)^CubismEditor5(?:[-_]?D3D)?\.bat$'
    $stagedEntry = $false
    if ($null -ne $home -and $officialName -match '(?i)^\.turboism-java-[0-9]+-[0-9a-f]{32}\.bat$') {
        $stage = Join-Path (Join-Path $home "state") "managed-launch"
        $stagedEntry = $officialDirectory -ieq $stage.TrimEnd('\', '/')
    }
    if ($null -eq $root -or $null -eq $official -or (-not $rootEntry -and -not $stagedEntry) -or
        -not (Test-CubismNormalFile $official)) { throw "Cubism BAT is not an admitted managed launch entry" }

    $oldJdk = $env:JDK_JAVA_OPTIONS
    $oldTool = $env:JAVA_TOOL_OPTIONS
    $exitCode = 1
    try {
        $managedOptions = @(
            "-Dturboism.home=$TurboismHome",
            "-javaagent:$Agent=home=$TurboismHome;timeoutSeconds=120"
        )
        if ($null -ne $GraalHost) {
            $managedOptions += @(
                "-Dturboism.graal.enabled=true",
                "-Dturboism.graal.java=$($GraalHost.Java)",
                "-Dturboism.graal.classpath=$($GraalHost.ClassPath)"
            )
        }
        else { $managedOptions += "-Dturboism.graal.enabled=false" }
        $managed = @($managedOptions + @(Get-CubismManagedJdkExportTokens)) |
            ForEach-Object { ConvertTo-JdkOptionToken $_ }
        $unrelatedJdk = Remove-TurboismJdkOptions $oldJdk
        $env:JDK_JAVA_OPTIONS = ((@($unrelatedJdk) + $managed) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
        if ($null -ne $oldTool) { $env:JAVA_TOOL_OPTIONS = Remove-TurboismJdkOptions $oldTool }
        Push-Location -LiteralPath $root
        try {
            & $official @Arguments
            $exitCode = $LASTEXITCODE
        }
        finally { Pop-Location }
    }
    finally {
        if ($null -eq $oldJdk) { Remove-Item Env:JDK_JAVA_OPTIONS -ErrorAction SilentlyContinue }
        else { $env:JDK_JAVA_OPTIONS = $oldJdk }
        if ($null -eq $oldTool) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
        else { $env:JAVA_TOOL_OPTIONS = $oldTool }
    }
    return $exitCode
}
