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
    return $null -ne (Get-CubismVersionFromPath $Root) -or (Test-CubismRequiredFileShape $Root)
}

function Test-CubismRequiredFileShape {
    param([string]$Root)
    if ($null -eq $Root -or -not (Test-CubismFixedDrive $Root)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $Root "CubismEditor5.bat") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root "app\jre\bin\java.exe") -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $Root "app\lib\Live2D_Cubism.jar") -PathType Leaf)
}

function Test-CubismAutoCandidatePath {
    param([string]$Root)
    if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-CubismFixedDrive $Root)) { return $false }
    return (Get-CubismVersionFromPath $Root) -or (Test-CubismRequiredFileShape $Root)
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
    return [regex]::IsMatch($Name, '(?i)^(?:Program Files(?: \(x86\))?|ProgramData|Users|Applications?|Apps?|Software|Games|Programs|Live2D|Cubism.*)$')
}

function Get-CubismDirectories {
    param([string]$Parent)
    try {
        return @(Get-ChildItem -LiteralPath $Parent -Directory -Force -ErrorAction SilentlyContinue |
            Where-Object { ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 } |
            Sort-Object FullName | Select-Object -First 64)
    }
    catch { return @() }
}

function Get-CubismDiscoveryRoots {
    param(
        [string[]]$SavedRoots = @(),
        [string[]]$ManualRoots = @()
    )
    $roots = New-Object System.Collections.Generic.List[string]
    $keys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = {
        param([string]$Root, [bool]$Explicit)
        if ($roots.Count -ge $script:CubismMaxRoots -or [string]::IsNullOrWhiteSpace($Root)) { return }
        $canonical = ConvertTo-CubismCanonicalRoot $Root
        if ($null -eq $canonical -or -not $Explicit -and -not (Test-CubismAutoCandidatePath $canonical)) { return }
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
            if (Test-CubismScanContainer $child.Name) {
                & $add $child.FullName $false
                foreach ($grandchild in Get-CubismDirectories $child.FullName) {
                    if (Test-CubismScanContainer $grandchild.Name) { & $add $grandchild.FullName $false }
                }
            }
        }
    }

    # Fixed-drive scan is deliberately shallow and only traverses named containers.
    # It never adds arbitrary directories and stops as soon as the root bound is met.
    try {
        $drives = @([System.IO.DriveInfo]::GetDrives() |
            Where-Object { $_.DriveType -eq [System.IO.DriveType]::Fixed } |
            Sort-Object Name | Select-Object -First 26)
        foreach ($drive in $drives) {
            foreach ($top in Get-CubismDirectories $drive.RootDirectory.FullName) {
                if (-not (Test-CubismScanContainer $top.Name)) { continue }
                & $add $top.FullName $false
                foreach ($one in Get-CubismDirectories $top.FullName) {
                    if (-not (Test-CubismScanContainer $one.Name)) { continue }
                    & $add $one.FullName $false
                    foreach ($two in Get-CubismDirectories $one.FullName) {
                        if (Test-CubismScanContainer $two.Name) { & $add $two.FullName $false }
                    }
                }
                if ($roots.Count -ge $script:CubismMaxRoots) { break }
            }
            if ($roots.Count -ge $script:CubismMaxRoots) { break }
        }
    }
    catch { }

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
    $empty = [pscustomobject]@{ Exists = $false; Valid = $true; Installations = @(); ManagedShortcuts = @(); Error = "" }
    try { $item = Get-Item -LiteralPath $StatePath -Force -ErrorAction Stop }
    catch [System.Management.Automation.ItemNotFoundException] { return $empty }
    catch {
        $empty.Exists = $true
        $empty.Valid = $false
        $empty.Error = $_.Exception.Message
        return $empty
    }
    $empty.Exists = $true
    try {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "state file is a reparse point" }
        if ($item.PSIsContainer -or -not [System.IO.File]::Exists($StatePath)) { throw "state file is not regular" }
        $doc = Read-CubismStateBytes $StatePath | ConvertFrom-Json -ErrorAction Stop
        if ($null -eq $doc) { throw "state JSON is empty" }
        $stateKeys = @($doc.PSObject.Properties.Name)
        $requiredKeys = @("format", "schemaVersion", "installations", "managedShortcuts")
        if ($stateKeys.Count -ne $requiredKeys.Count -or @($requiredKeys | Where-Object { $stateKeys -notcontains $_ }).Count -gt 0) {
            throw "unknown or missing state fields"
        }
        if ($doc.format -ne $script:CubismStateFormat) { throw "unsupported state format" }
        if (($doc.schemaVersion -isnot [int]) -and ($doc.schemaVersion -isnot [long])) { throw "unsupported state schema" }
        if ([int64]$doc.schemaVersion -ne $script:CubismStateSchemaVersion) { throw "unsupported state schema" }
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
            if ($entry.root.Length -eq 0 -or $entry.root.Length -gt $script:CubismMaxStateFieldLength -or
                [string]::IsNullOrWhiteSpace($entry.version) -and $entry.version.Length -gt 0 -or
                $entry.version.Length -gt 16) { throw "invalid installation entry bounds" }
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
        $empty.Installations = @($installations)
        $empty.ManagedShortcuts = @($shortcuts)
        return $empty
    }
    catch {
        $empty.Valid = $false
        $empty.Installations = @()
        $empty.ManagedShortcuts = @()
        $empty.Error = $_.Exception.Message
        return $empty
    }
}

function Write-CubismInstallationState {
    param(
        [string]$StatePath,
        [object[]]$Candidates,
        [string[]]$ManagedShortcuts = @()
    )
    $parent = Split-Path -Parent $StatePath
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw "Turboism home does not exist: $parent" }
    $selectedCandidates = @($Candidates | Select-Object -First $script:CubismMaxStateEntries)
    if (@($Candidates).Count -gt $script:CubismMaxStateEntries) { throw "too many installation entries" }
    $stateRootKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $entries = @($selectedCandidates | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace($_.CanonicalRoot) -or $_.CanonicalRoot.Length -gt $script:CubismMaxStateFieldLength) { throw "invalid installation root" }
        $rootKey = Get-CubismRootKey $_.CanonicalRoot
        if ($null -eq $rootKey -or -not $stateRootKeys.Add($rootKey)) { throw "duplicate installation root" }
        if ($_.Version.Length -gt 16) { throw "invalid installation version" }
        [ordered]@{ root = $_.CanonicalRoot; version = [string]$_.Version; selected = [bool]$_.Selected }
    })
    $owned = @($ManagedShortcuts)
    if ($owned.Count -gt $script:CubismMaxShortcutEntries) { throw "too many managed shortcuts" }
    $shortcutKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($shortcut in $owned) {
        if ([string]::IsNullOrWhiteSpace($shortcut) -or $shortcut.Length -gt $script:CubismMaxStateFieldLength -or
            -not (Test-CubismManagedShortcutPath $shortcut)) { throw "invalid managed shortcut path" }
        $shortcutKey = [System.IO.Path]::GetFullPath($shortcut).ToUpperInvariant()
        if (-not $shortcutKeys.Add($shortcutKey)) { throw "duplicate managed shortcut path" }
    }
    $doc = [ordered]@{
        format = $script:CubismStateFormat
        schemaVersion = $script:CubismStateSchemaVersion
        installations = $entries
        managedShortcuts = @($owned)
    }
    $text = $doc | ConvertTo-Json -Depth 5 -Compress
    $encoding = New-Object System.Text.UTF8Encoding($false)
    if ($encoding.GetByteCount($text) -gt $script:CubismMaxStateBytes) { throw "state JSON exceeds $($script:CubismMaxStateBytes) bytes" }
    $temporary = "$StatePath.$PID.tmp"
    try {
        [System.IO.File]::WriteAllText($temporary, $text, $encoding)
        if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
            $target = Get-Item -LiteralPath $StatePath -Force -ErrorAction Stop
            if (($target.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "state file is a reparse point" }
            [System.IO.File]::Replace($temporary, $StatePath, $null, $true)
        }
        else { [System.IO.File]::Move($temporary, $StatePath) }
    }
    finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
    }
}

function Get-CubismShortcutDirectory {
    param([string]$Override = "")
    if (-not [string]::IsNullOrWhiteSpace($Override)) { return [System.IO.Path]::GetFullPath($Override) }
    $programs = [Environment]::GetFolderPath("Programs")
    if ([string]::IsNullOrWhiteSpace($programs)) { $programs = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs" }
    return (Join-Path $programs "Turboism")
}

function Get-CubismShortcutName {
    param([object]$Candidate, [string]$Variant = "normal")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Candidate.CanonicalRoot.ToUpperInvariant())
        $hash = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").Substring(0, 12)
    }
    finally { $sha.Dispose() }
    $identity = $Candidate.CanonicalRoot -replace '[:\\/]+', '_' -replace '[^\p{L}\p{Nd}._ -]', '_' -replace '\s+', '_'
    if ($identity.Length -gt 48) { $identity = $identity.Substring($identity.Length - 48) }
    $suffix = if ($Variant -eq "d3d") { " - D3D" } else { "" }
    return "Turboism Cubism $($Candidate.Version) [$identity-$hash]$suffix.lnk"
}

function New-CubismManagedShortcut {
    param(
        [string]$Home,
        [object]$Candidate,
        [string]$Variant = "normal",
        [string]$ShortcutDirectory = ""
    )
    $shortcutDirectory = Get-CubismShortcutDirectory -Override $ShortcutDirectory
    if (Test-Path -LiteralPath $shortcutDirectory) {
        $directoryItem = Get-Item -LiteralPath $shortcutDirectory -Force -ErrorAction Stop
        if (($directoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
            -not $directoryItem.PSIsContainer) { throw "shortcut directory is not a normal directory" }
    }
    else { New-Item -ItemType Directory -Path $shortcutDirectory -Force | Out-Null }
    $path = Join-Path $shortcutDirectory (Get-CubismShortcutName $Candidate $Variant)
    try { $existing = Get-Item -LiteralPath $path -Force -ErrorAction Stop }
    catch [System.Management.Automation.ItemNotFoundException] { $existing = $null }
    catch { throw }
    if ($null -ne $existing -and (($existing.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
            -or $existing.PSIsContainer -or -not [System.IO.File]::Exists($path))) { throw "shortcut path is not a regular file" }
    $powershell = Join-Path $env:WINDIR "System32\WindowsPowerShell\v1.0\powershell.exe"
    $scriptPath = Join-Path $PSScriptRoot "launch-cubism-turboism.ps1"
    $quote = { param([string]$value) '"' + $value.Replace('"', '\"') + '"' }
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File $(& $quote $scriptPath) -Home $(& $quote $Home) -CubismRoot $(& $quote $Candidate.CanonicalRoot)"
    if ($Variant -eq "d3d") { $arguments += " -Variant d3d" }
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($path)
    $shortcut.TargetPath = $powershell
    $shortcut.Arguments = $arguments
    $shortcut.WorkingDirectory = $Home
    $shortcut.Description = "Turboism managed Cubism $($Candidate.Version) launch"
    $shortcut.Save()
    return $path
}

function Remove-CubismManagedShortcuts {
    param(
        [string[]]$Paths = @(),
        [string]$Directory = ""
    )
    $failed = New-Object System.Collections.Generic.List[string]
    foreach ($path in @($Paths)) {
        try {
            if (-not (Test-CubismManagedShortcutPath $path -Directory $Directory)) { continue }
            $full = [System.IO.Path]::GetFullPath($path)
            if (Test-Path -LiteralPath $full -PathType Leaf) {
                Remove-Item -LiteralPath $full -Force -ErrorAction Stop
                if (Test-Path -LiteralPath $full -PathType Leaf) { [void]$failed.Add($path) }
            }
        }
        catch { [void]$failed.Add($path) }
    }
    return @($failed)
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
                $builder.Clear()
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
            $probe -match '(?i)^-javaagent:.*turboism-agent\.jar(?:[=].*)?$' -or
            $probe -match '(?i)^--add-exports=java\.base\.jdk\.internal\.org\.objectweb\.asm(?:\.commons)?=ALL-UNNAMED$') { continue }
        [void]$kept.Add($token)
    }
    return ($kept -join " ").Trim()
}

function ConvertTo-JdkOptionToken {
    param([string]$Option)
    if ([string]::IsNullOrWhiteSpace($Option) -or $Option.Length -gt $script:CubismMaxJdkOptionLength -or $Option -match '[\r\n]') { throw "invalid managed JVM option" }
    if ($Option -match '[\s"]') { return '"' + $Option.Replace('"', '\"') + '"' }
    return $Option
}

function Invoke-CubismOfficialBat {
    param(
        [string]$OfficialBat,
        [string]$CubismRoot,
        [string]$Home,
        [string]$Agent,
        [string[]]$Arguments = @()
    )
    if ([string]::IsNullOrWhiteSpace($Home) -or [string]::IsNullOrWhiteSpace($Agent) -or
        $Home.Length -gt $script:CubismMaxStateFieldLength -or $Agent.Length -gt $script:CubismMaxStateFieldLength) { throw "launch path exceeds bound" }
    if (@($Arguments).Count -gt $script:CubismMaxLaunchArguments -or @($Arguments | Where-Object { $_.Length -gt $script:CubismMaxStateFieldLength }).Count -gt 0) { throw "launch arguments exceed bound" }
    $root = ConvertTo-CubismCanonicalRoot $CubismRoot
    $official = $null
    try { $official = [System.IO.Path]::GetFullPath($OfficialBat) } catch { }
    if ($null -eq $root -or $null -eq $official -or
        [System.IO.Path]::GetDirectoryName($official).TrimEnd('\', '/') -ine $root.TrimEnd('\', '/') -or
        [System.IO.Path]::GetFileName($official) -notmatch '(?i)^CubismEditor5(?:_D3D)?\.bat$' -or
        -not (Test-Path -LiteralPath $official -PathType Leaf)) { throw "official Cubism BAT is not the selected root entry" }

    $oldJdk = $env:JDK_JAVA_OPTIONS
    $oldTool = $env:JAVA_TOOL_OPTIONS
    $exitCode = 1
    try {
        $managed = @(
            "-Dturboism.home=$Home",
            "-javaagent:$Agent=home=$Home;timeoutSeconds=120",
            "--add-exports=java.base.jdk.internal.org.objectweb.asm=ALL-UNNAMED",
            "--add-exports=java.base.jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
        ) | ForEach-Object { ConvertTo-JdkOptionToken $_ }
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
