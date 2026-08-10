# -*- coding: utf-8 -*-
# Shared, synthetic-testable Windows Cubism inventory and managed-launch helpers.
# This file never writes inside a Cubism root.

$script:CubismStateFormat = "turboism.cubism.installation-state"
$script:CubismStateSchemaVersion = 1
$script:CubismMaxRoots = 256
$script:CubismMaxStateBytes = 65536

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
    $match = [regex]::Match($Root, '(?<!\d)5\.(?:2\.03|3\.02)(?!\d)')
    if ($match.Success) { return $match.Value }
    return $null
}

function Get-CubismD3DBat {
    param([string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return $null }
    try {
        $batMatches = @(
            Get-ChildItem -LiteralPath $Root -File -ErrorAction SilentlyContinue |
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
        $reason = "Only Cubism 5.2.03 and 5.3.02 paths are selectable."
    }
    elseif ($missing.Count -gt 0) {
        $status = "Invalid"
        $reason = "Missing: " + ($missing -join ", ") + "."
    }
    else {
        $status = "Ready"
        $reason = ""
    }

    return [pscustomobject]@{
        Root = $canonical; CanonicalRoot = $canonical; Key = (Get-CubismRootKey $canonical)
        Version = $(if ($null -eq $version) { "" } else { $version }); Source = $Source
        Status = $status; Reason = $reason; Selectable = ($status -eq "Ready")
        OfficialBat = $officialBat; D3DBat = (Get-CubismD3DBat $canonical)
        Java = $java; ApplicationJar = $applicationJar; Selected = $false
    }
}

function Get-CubismDiscoveryRoots {
    param(
        [string[]]$SavedRoots = @(),
        [string[]]$ManualRoots = @()
    )
    $roots = New-Object System.Collections.Generic.List[string]
    $keys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = {
        param([string]$Root)
        if ($roots.Count -ge $script:CubismMaxRoots -or [string]::IsNullOrWhiteSpace($Root)) { return }
        $canonical = ConvertTo-CubismCanonicalRoot $Root
        if ($null -ne $canonical -and $keys.Add($canonical)) { [void]$roots.Add($canonical) }
    }

    foreach ($root in @($SavedRoots)) { & $add $root }
    if (-not [string]::IsNullOrWhiteSpace($env:CUBISM_ROOT)) { & $add $env:CUBISM_ROOT }
    foreach ($root in @($ManualRoots)) { & $add $root }

    $registryPaths = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    foreach ($path in $registryPaths) {
        try {
            $entries = @(Get-ItemProperty -Path $path -ErrorAction SilentlyContinue | Select-Object -First 64)
            foreach ($entry in $entries) {
                foreach ($property in @("InstallLocation", "InstallDir", "Location")) {
                    $value = $entry.$property
                    if ($value -is [string] -and -not [string]::IsNullOrWhiteSpace($value)) { & $add $value }
                }
            }
        }
        catch { }
    }

    $knownBases = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:ProgramW6432, $env:LOCALAPPDATA) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    foreach ($base in $knownBases) {
        foreach ($relative in @("Live2D", "Live2D Cubism", "Live2D\Cubism", "Cubism")) {
            & $add (Join-Path $base $relative)
        }
        try {
            @(Get-ChildItem -LiteralPath $base -Directory -Force -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '(?i)Live2D|Cubism' } | Select-Object -First 64) |
                ForEach-Object { & $add $_.FullName }
        }
        catch { }
    }

    # Bounded fixed-drive scan: root plus two directory levels, no reparse points.
    try {
        $drives = [System.IO.DriveInfo]::GetDrives() |
            Where-Object { $_.DriveType -eq [System.IO.DriveType]::Fixed } |
            Select-Object -First 26
        foreach ($drive in $drives) {
            $top = @(Get-ChildItem -LiteralPath $drive.RootDirectory.FullName -Directory -Force -ErrorAction SilentlyContinue |
                Where-Object { ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 } |
                Select-Object -First 64)
            foreach ($one in $top) {
                & $add $one.FullName
                $children = @(Get-ChildItem -LiteralPath $one.FullName -Directory -Force -ErrorAction SilentlyContinue |
                    Where-Object { ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 } |
                    Select-Object -First 64)
                foreach ($two in $children) { & $add $two.FullName }
            }
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

function Read-CubismInstallationState {
    param([string]$StatePath)
    $empty = [pscustomobject]@{ Exists = $false; Valid = $true; Installations = @(); ManagedShortcuts = @(); Error = "" }
    if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) { return $empty }
    $empty.Exists = $true
    try {
        $item = Get-Item -LiteralPath $StatePath -Force -ErrorAction Stop
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "state file is a reparse point" }
        if ($item.Length -gt $script:CubismMaxStateBytes) { throw "state file is too large" }
        $doc = Get-Content -LiteralPath $StatePath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($doc.format -ne $script:CubismStateFormat -or [int]$doc.schemaVersion -ne $script:CubismStateSchemaVersion) {
            throw "unsupported state schema"
        }
        $installations = @()
        foreach ($entry in @($doc.installations)) {
            if ($entry.root -is [string] -and -not [string]::IsNullOrWhiteSpace($entry.root)) {
                $installations += [pscustomobject]@{
                    Root = (ConvertTo-CubismCanonicalRoot $entry.root)
                    Version = [string]$entry.version
                    Selected = [bool]$entry.selected
                }
            }
        }
        $shortcuts = @($doc.managedShortcuts | Where-Object { $_ -is [string] })
        $empty.Installations = @($installations | Select-Object -First $script:CubismMaxRoots)
        $empty.ManagedShortcuts = @($shortcuts | Select-Object -First 512)
        return $empty
    }
    catch {
        $empty.Valid = $false
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
    $entries = @($Candidates | Select-Object -First $script:CubismMaxRoots | ForEach-Object {
        [ordered]@{ root = $_.CanonicalRoot; version = $_.Version; selected = [bool]$_.Selected }
    })
    $doc = [ordered]@{
        format = $script:CubismStateFormat
        schemaVersion = $script:CubismStateSchemaVersion
        installations = $entries
        managedShortcuts = @($ManagedShortcuts | Select-Object -First 512)
    }
    $text = $doc | ConvertTo-Json -Depth 5
    $temporary = "$StatePath.$PID.tmp"
    try {
        [System.IO.File]::WriteAllText($temporary, $text, [System.Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
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
        [string]$Variant = "normal"
    )
    $shortcutDirectory = Get-CubismShortcutDirectory
    New-Item -ItemType Directory -Path $shortcutDirectory -Force | Out-Null
    $path = Join-Path $shortcutDirectory (Get-CubismShortcutName $Candidate $Variant)
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
    $directory = Get-CubismShortcutDirectory -Override $Directory
    $prefix = ([System.IO.Path]::GetFullPath($directory)).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    foreach ($path in @($Paths)) {
        try {
            $full = [System.IO.Path]::GetFullPath($path)
            if ($full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) -and
                [System.IO.Path]::GetExtension($full) -ieq ".lnk" -and
                ([System.IO.Path]::GetFileName($full)).StartsWith("Turboism Cubism ", [System.StringComparison]::OrdinalIgnoreCase)) {
                Remove-Item -LiteralPath $full -Force -ErrorAction SilentlyContinue
            }
        }
        catch { }
    }
}

function Get-JdkOptionTokens {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    return @([regex]::Matches($Text, '(?:"[^"]*"|[^\s"]+)+') | ForEach-Object { $_.Value })
}

function Remove-TurboismJdkOptions {
    param([string]$Text)
    $kept = @()
    foreach ($token in @(Get-JdkOptionTokens $Text)) {
        $value = $token.Trim('"')
        if ($value -match '(?i)-javaagent:.*turboism-agent\.jar' -or $value -match '(?i)-Dturboism\.home=') { continue }
        $kept += $token
    }
    return ($kept -join " ").Trim()
}

function ConvertTo-JdkOptionToken {
    param([string]$Option)
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
    $oldJdk = $env:JDK_JAVA_OPTIONS
    $oldTool = $env:JAVA_TOOL_OPTIONS
    $exitCode = 1
    try {
        $managed = @(
            "-Dturboism.home=$Home",
            "-javaagent:$Agent=home=$Home;timeoutSeconds=120",
            "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
        ) | ForEach-Object { ConvertTo-JdkOptionToken $_ }
        $unrelatedJdk = Remove-TurboismJdkOptions $oldJdk
        $env:JDK_JAVA_OPTIONS = ((@($unrelatedJdk) + $managed) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
        if ($null -ne $oldTool) { $env:JAVA_TOOL_OPTIONS = Remove-TurboismJdkOptions $oldTool }
        Push-Location -LiteralPath $CubismRoot
        try {
            & $OfficialBat @Arguments
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
