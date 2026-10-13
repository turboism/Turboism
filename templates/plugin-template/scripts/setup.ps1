# Setup script for a standalone Turboism plugin project created from
# turboism/turboism-plugin-template (or templates/plugin-template/).
#
# Steps:
#   1. Choose the release channel (stable or beta, default stable) or an explicit version.
#   2. Resolve the release via https://api.turboism.dev/v1/releases/<channel>.json,
#      falling back to the GitHub Releases API.
#   3. Download turboism-sdk-<v>.jar and turboism-event-processor-<v>.jar with their
#      .sha256 sidecars from the GitHub release asset URLs.
#   4. Verify SHA-256 digests, place the verified JARs into libs/, and record
#      turboismSdkVersion=<v> in gradle.properties.
#   5. Prompt interactively (or read CLI flags for non-interactive/CI use) for plugin id,
#      display name, Java package, and author; rename the package directory and plugin
#      class accordingly and update plugin.json, i18n catalogs, and Gradle metadata.
#   6. Write META-INF/turboism/cubism-versions.json using the release API's
#      compatibility.cubismEditor.versions (or the default reviewed version set).

[CmdletBinding()]
param(
    [string]$Channel = '',
    [string]$Version = '',
    [string]$Id = '',
    [string]$Name = '',
    [string]$Package = '',
    [string]$ClassName = '',
    [string]$Author = '',
    [string]$RequiresCubism = '',
    [string]$CubismVersions = '',
    [Alias('y')][switch]$Yes,
    [switch]$NonInteractive,
    [Alias('h')][switch]$Help,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$RemainingArgs = @()
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Show-Usage {
    @"
Usage: .\scripts\setup.ps1 [options]

Options:
  -Channel, --channel <stable|beta>       Release channel to resolve (default: stable)
  -Version, --version <version>           Explicit SDK/framework version (e.g. 0.67.0 or 0.67.0-beta.2)
  -Id, --id <plugin-id>                   Reverse-domain plugin ID (e.g. dev.example.hello)
  -Name, --name <display-name>            Human-readable plugin name (e.g. "Hello Plugin")
  -Package, --package <java-package>      Java package for the entrypoint class (e.g. dev.example.hello)
  -ClassName, --class-name <ClassName>    Java entrypoint class simple name (e.g. HelloPlugin)
  -Author, --author <author-name>         Plugin author name (e.g. "Your Name")
  -RequiresCubism, --requires-cubism <b>  Set environment.requiresCubism in plugin.json (true or false)
  -CubismVersions, --cubism-versions <v>  Comma-separated supported Cubism Editor versions
  -Yes, -NonInteractive, -y, --yes        Run without interactive prompts, using flags or defaults
  -Help, -h, --help                       Show this help message
"@ | Write-Host
}

for ($i = 0; $i -lt $RemainingArgs.Count; $i++) {
    $arg = $RemainingArgs[$i]
    switch -Regex ($arg) {
        '^--channel$'         { $i++; $Channel = $RemainingArgs[$i]; continue }
        '^--channel=(.+)$'    { $Channel = $Matches[1]; continue }
        '^--version$'         { $i++; $Version = $RemainingArgs[$i]; continue }
        '^--version=(.+)$'    { $Version = $Matches[1]; continue }
        '^--id$'              { $i++; $Id = $RemainingArgs[$i]; continue }
        '^--id=(.+)$'         { $Id = $Matches[1]; continue }
        '^--name$'            { $i++; $Name = $RemainingArgs[$i]; continue }
        '^--name=(.+)$'       { $Name = $Matches[1]; continue }
        '^--package$'         { $i++; $Package = $RemainingArgs[$i]; continue }
        '^--package=(.+)$'    { $Package = $Matches[1]; continue }
        '^--class-name$'      { $i++; $ClassName = $RemainingArgs[$i]; continue }
        '^--class-name=(.+)$' { $ClassName = $Matches[1]; continue }
        '^--author$'          { $i++; $Author = $RemainingArgs[$i]; continue }
        '^--author=(.+)$'     { $Author = $Matches[1]; continue }
        '^--requires-cubism$' { $i++; $RequiresCubism = $RemainingArgs[$i]; continue }
        '^--requires-cubism=(.+)$' { $RequiresCubism = $Matches[1]; continue }
        '^--cubism-versions$' { $i++; $CubismVersions = $RemainingArgs[$i]; continue }
        '^--cubism-versions=(.+)$' { $CubismVersions = $Matches[1]; continue }
        '^(-y|--yes|--non-interactive)$' { $NonInteractive = $true; continue }
        '^(-h|--help)$'       { $Help = $true; continue }
        default               { throw "Unknown option: $arg (run with -Help for usage)" }
    }
}

if ($Help) {
    Show-Usage
    exit 0
}

if ($Yes -or $env:CI -eq 'true' -or -not [Environment]::UserInteractive) {
    $NonInteractive = $true
}

$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$ReleaseApiBase = if ($env:TURBOISM_SETUP_RELEASE_API_URL) { $env:TURBOISM_SETUP_RELEASE_API_URL.TrimEnd('/') } else { 'https://api.turboism.dev/v1/releases' }
$GitHubApiBase = if ($env:TURBOISM_SETUP_GITHUB_API_URL) { $env:TURBOISM_SETUP_GITHUB_API_URL.TrimEnd('/') } else { 'https://api.github.com/repos/turboism/Turboism/releases' }
$DownloadBase = if ($env:TURBOISM_SETUP_DOWNLOAD_BASE_URL) { $env:TURBOISM_SETUP_DOWNLOAD_BASE_URL.TrimEnd('/') } else { 'https://github.com/turboism/Turboism/releases/download' }

$DefaultReviewedVersions = @(
    '5.0.00', '5.0.01', '5.0.02', '5.0.03', '5.0.04', '5.0.05', '5.0.06',
    '5.1.00', '5.1.01', '5.1.02', '5.1.03',
    '5.2.00', '5.2.01', '5.2.02', '5.2.03',
    '5.3.00', '5.3.01', '5.3.02', '5.3.03', '5.3.04'
)

function Assert-SafeUrl {
    param([string]$Url)
    if ($Url -match '^https://') { return }
    $hasOverride = [bool]($env:TURBOISM_SETUP_RELEASE_API_URL -or $env:TURBOISM_SETUP_GITHUB_API_URL -or $env:TURBOISM_SETUP_DOWNLOAD_BASE_URL)
    if ($hasOverride -and ($Url -match '^file://' -or $Url -match '^http://(127\.0\.0\.1|localhost)(:[0-9]+)?/')) {
        return
    }
    throw "Refusing non-HTTPS URL: $Url"
}

function Invoke-SafeDownload {
    param([string]$Url, [string]$Destination)
    Assert-SafeUrl -Url $Url
    if ($Url -match '^file://') {
        $localPath = ([Uri]$Url).LocalPath
        if (-not (Test-Path -LiteralPath $localPath)) {
            throw "Local file not found: $localPath"
        }
        Copy-Item -LiteralPath $localPath -Destination $Destination -Force
        return
    }
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -TimeoutSec 120 -ErrorAction Stop
}

function Assert-ChecksumSidecar {
    param([string]$JarFile, [string]$ShaFile, [string]$ExpectedName)
    if (-not (Test-Path -LiteralPath $JarFile) -or (Get-Item -LiteralPath $JarFile).Length -le 0) {
        throw "Downloaded artifact is missing or empty: $ExpectedName"
    }
    if (-not (Test-Path -LiteralPath $ShaFile) -or (Get-Item -LiteralPath $ShaFile).Length -le 0) {
        throw "Downloaded checksum sidecar is missing or empty: $ExpectedName.sha256"
    }
    $firstLine = (Get-Content -LiteralPath $ShaFile -TotalCount 1 -Encoding UTF8).Trim()
    $expectedHash = ($firstLine -split '\s+')[0].ToLowerInvariant()
    if ($expectedHash -notmatch '^[0-9a-f]{64}$') {
        throw "Malformed SHA-256 sidecar for ${ExpectedName}: $firstLine"
    }
    $actualHash = (Get-FileHash -LiteralPath $JarFile -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    if ($actualHash -cne $expectedHash) {
        throw "SHA-256 mismatch for ${ExpectedName}: expected $expectedHash, got $actualHash"
    }
}

function Read-PromptValue {
    param([string]$Label, [string]$DefaultValue)
    $reply = Read-Host -Prompt "$Label [$DefaultValue]"
    if ([string]::IsNullOrWhiteSpace($reply)) { return $DefaultValue }
    return $reply.Trim()
}

$PluginJsonPath = Join-Path $ProjectRoot 'src/main/resources/META-INF/turboism/plugin.json'
if (-not (Test-Path -LiteralPath $PluginJsonPath)) {
    throw "Missing plugin descriptor: $PluginJsonPath"
}
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$CurrentMeta = [System.IO.File]::ReadAllText($PluginJsonPath, $Utf8NoBom) | ConvertFrom-Json
$CurId = if ($CurrentMeta.id) { [string]$CurrentMeta.id } else { 'dev.example.hello' }
$CurName = if ($CurrentMeta.name) { [string]$CurrentMeta.name } else { 'Hello Plugin' }
$CurEntrypoint = if ($CurrentMeta.entrypoints -and $CurrentMeta.entrypoints.Count -gt 0) { [string]$CurrentMeta.entrypoints[0] } else { 'dev.example.hello.HelloPlugin' }
$lastDot = $CurEntrypoint.LastIndexOf('.')
$CurPackage = if ($lastDot -gt 0) { $CurEntrypoint.Substring(0, $lastDot) } else { 'dev.example.hello' }
$CurClass = if ($lastDot -ge 0) { $CurEntrypoint.Substring($lastDot + 1) } else { 'HelloPlugin' }
$CurAuthor = if ($CurrentMeta.authors -and $CurrentMeta.authors.Count -gt 0 -and $CurrentMeta.authors[0].name) { [string]$CurrentMeta.authors[0].name } else { 'Your Name' }
$CurRequiresCubism = if ($CurrentMeta.environment -and $CurrentMeta.environment.requiresCubism) { 'true' } else { 'false' }

if ($Version.StartsWith('v')) {
    $Version = $Version.Substring(1)
}

if ([string]::IsNullOrWhiteSpace($Version) -and [string]::IsNullOrWhiteSpace($Channel)) {
    if (-not $NonInteractive) {
        $choice = Read-PromptValue -Label 'Release channel (stable/beta) or explicit version' -DefaultValue 'stable'
        if ($choice -eq 'stable' -or $choice -eq 'beta') {
            $Channel = $choice
        } else {
            $Version = $choice.TrimStart('v')
        }
    } else {
        $Channel = 'stable'
    }
}

if (-not [string]::IsNullOrWhiteSpace($Version)) {
    if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$') {
        throw "Invalid version format: $Version"
    }
    if ([string]::IsNullOrWhiteSpace($Channel)) {
        $Channel = if ($Version.Contains('-')) { 'beta' } else { 'stable' }
    }
} elseif ($Channel -ne 'stable' -and $Channel -ne 'beta') {
    throw "Unsupported channel: $Channel (expected stable or beta)"
}

$WorkTmp = Join-Path ([System.IO.Path]::GetTempPath()) ("turboism-plugin-setup-" + [Guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($WorkTmp)

try {
    $ResolvedVersion = ''
    $ResolvedCompatVersions = @()

    # Step 2: Resolve via release API, falling back to GitHub Releases API.
    $apiFile = Join-Path $WorkTmp 'release-api.json'
    try {
        Invoke-SafeDownload -Url "$ReleaseApiBase/$Channel.json" -Destination $apiFile
        $doc = [System.IO.File]::ReadAllText($apiFile, $Utf8NoBom) | ConvertFrom-Json
        if ($doc -and $doc.status -eq 'ready' -and $doc.release -and $doc.release.version) {
            $candidateVer = [string]$doc.release.version
            if ($candidateVer -match '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$' -and ([string]::IsNullOrWhiteSpace($Version) -or $candidateVer -eq $Version)) {
                $ResolvedVersion = $candidateVer
                if ($doc.release.compatibility -and $doc.release.compatibility.cubismEditor -and $doc.release.compatibility.cubismEditor.versions) {
                    $ResolvedCompatVersions = @($doc.release.compatibility.cubismEditor.versions | Where-Object { $_ -match '^5\.\d{1,2}\.\d{2}$' })
                }
                Write-Host "Resolved version $ResolvedVersion via release API."
            }
        }
    } catch {
        # Fall back to GitHub Releases API
    }

    if ([string]::IsNullOrWhiteSpace($ResolvedVersion)) {
        $ghFile = Join-Path $WorkTmp 'github-releases.json'
        $ghUrl = if (-not [string]::IsNullOrWhiteSpace($Version)) {
            "$GitHubApiBase/tags/v$Version"
        } elseif ($Channel -eq 'stable') {
            "$GitHubApiBase/latest"
        } else {
            "$GitHubApiBase?per_page=30"
        }
        try {
            Invoke-SafeDownload -Url $ghUrl -Destination $ghFile
            $raw = [System.IO.File]::ReadAllText($ghFile, $Utf8NoBom) | ConvertFrom-Json
            $items = if ($raw -is [array]) { $raw } else { @($raw) }
            foreach ($item in $items) {
                if (-not $item -or $item.draft -eq $true) { continue }
                $tag = [string]$item.tag_name
                if (-not $tag.StartsWith('v')) { continue }
                $ver = $tag.Substring(1)
                if ($ver -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$') { continue }
                if (-not [string]::IsNullOrWhiteSpace($Version)) {
                    if ($ver -eq $Version) {
                        $ResolvedVersion = $ver
                        $body = [string]$item.body
                        if ($body -match '<!-- turboism-compatibility-v1 ([\s\S]*?) -->') {
                            try {
                                $compat = $Matches[1] | ConvertFrom-Json
                                if ($compat.cubismEditor -and $compat.cubismEditor.versions) {
                                    $ResolvedCompatVersions = @($compat.cubismEditor.versions | Where-Object { $_ -match '^5\.\d{1,2}\.\d{2}$' })
                                }
                            } catch {}
                        }
                        break
                    }
                    continue
                }
                $isPre = [bool]$item.prerelease
                if (($Channel -eq 'stable' -and -not $isPre -and -not $ver.Contains('-')) -or
                    ($Channel -eq 'beta' -and $isPre -and -not $ver.Contains('-0.nightly.'))) {
                    $ResolvedVersion = $ver
                    $body = [string]$item.body
                    if ($body -match '<!-- turboism-compatibility-v1 ([\s\S]*?) -->') {
                        try {
                            $compat = $Matches[1] | ConvertFrom-Json
                            if ($compat.cubismEditor -and $compat.cubismEditor.versions) {
                                $ResolvedCompatVersions = @($compat.cubismEditor.versions | Where-Object { $_ -match '^5\.\d{1,2}\.\d{2}$' })
                            }
                        } catch {}
                    }
                    break
                }
            }
            if (-not [string]::IsNullOrWhiteSpace($ResolvedVersion)) {
                Write-Host "Resolved version $ResolvedVersion via GitHub Releases API."
            }
        } catch {
            if (-not [string]::IsNullOrWhiteSpace($Version)) {
                $ResolvedVersion = $Version
                Write-Host "Using explicit version $ResolvedVersion."
            } else {
                throw "Unable to resolve release for channel '$Channel' from release API or GitHub Releases API: $_"
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($ResolvedVersion)) {
        if (-not [string]::IsNullOrWhiteSpace($Version)) {
            $ResolvedVersion = $Version
        } else {
            throw "Unable to resolve release for channel '$Channel'"
        }
    }

    # Step 3 & 4: Download JARs and .sha256 sidecars, verify hashes, place in libs/.
    $Tag = "v$ResolvedVersion"
    $SdkJarName = "turboism-sdk-$ResolvedVersion.jar"
    $ProcJarName = "turboism-event-processor-$ResolvedVersion.jar"

    foreach ($artifact in @($SdkJarName, "$SdkJarName.sha256", $ProcJarName, "$ProcJarName.sha256")) {
        $url = "$DownloadBase/$Tag/$artifact"
        Write-Host "Downloading $artifact..."
        Invoke-SafeDownload -Url $url -Destination (Join-Path $WorkTmp $artifact)
    }

    Assert-ChecksumSidecar -JarFile (Join-Path $WorkTmp $SdkJarName) -ShaFile (Join-Path $WorkTmp "$SdkJarName.sha256") -ExpectedName $SdkJarName
    Assert-ChecksumSidecar -JarFile (Join-Path $WorkTmp $ProcJarName) -ShaFile (Join-Path $WorkTmp "$ProcJarName.sha256") -ExpectedName $ProcJarName

    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $sdkZip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $WorkTmp $SdkJarName))
    try {
        if ($null -eq $sdkZip.GetEntry('dev/turboism/sdk/plugin/TurboismPlugin.class')) {
            throw "SDK artifact lacks TurboismPlugin.class: $SdkJarName"
        }
    } finally {
        $sdkZip.Dispose()
    }
    $procZip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $WorkTmp $ProcJarName))
    try {
        if ($null -eq $procZip.GetEntry('dev/turboism/eventprocessor/SubscribeEventProcessor.class')) {
            throw "event-processor artifact lacks SubscribeEventProcessor.class: $ProcJarName"
        }
    } finally {
        $procZip.Dispose()
    }

    $LibsDir = Join-Path $ProjectRoot 'libs'
    [void][System.IO.Directory]::CreateDirectory($LibsDir)
    Get-ChildItem -LiteralPath $LibsDir -Filter 'turboism-sdk-*.jar*' -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem -LiteralPath $LibsDir -Filter 'turboism-event-processor-*.jar*' -ErrorAction SilentlyContinue | Remove-Item -Force

    foreach ($artifact in @($SdkJarName, "$SdkJarName.sha256", $ProcJarName, "$ProcJarName.sha256")) {
        Copy-Item -LiteralPath (Join-Path $WorkTmp $artifact) -Destination (Join-Path $LibsDir $artifact) -Force
    }
    Write-Host "Verified SHA-256 checksums and installed $SdkJarName and $ProcJarName into libs/."

    $GradlePropsPath = Join-Path $ProjectRoot 'gradle.properties'
    if (Test-Path -LiteralPath $GradlePropsPath) {
        $propsText = [System.IO.File]::ReadAllText($GradlePropsPath, $Utf8NoBom)
        if ($propsText -match '(?m)^turboismSdkVersion=') {
            $propsText = [regex]::Replace($propsText, '(?m)^turboismSdkVersion=.*$', "turboismSdkVersion=$ResolvedVersion")
        } else {
            $propsText = $propsText.TrimEnd("`r", "`n") + "`nturboismSdkVersion=$ResolvedVersion`n"
        }
    } else {
        $propsText = "turboismSdkVersion=$ResolvedVersion`n"
    }
    [System.IO.File]::WriteAllText($GradlePropsPath, $propsText, $Utf8NoBom)

    # Step 5: Gather plugin metadata and rename files.
    if ([string]::IsNullOrWhiteSpace($Id)) {
        $Id = if (-not $NonInteractive) { Read-PromptValue -Label 'Plugin ID (reverse-domain)' -DefaultValue $CurId } else { $CurId }
    }
    if ($Id -notmatch '^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$') {
        throw "Invalid plugin ID '$Id' (must be reverse-domain lowercase, e.g. dev.example.myplugin)"
    }

    if ([string]::IsNullOrWhiteSpace($Name)) {
        $Name = if (-not $NonInteractive) { Read-PromptValue -Label 'Plugin display name' -DefaultValue $CurName } else { $CurName }
    }

    $DefaultPkg = $CurPackage
    if ($Id -cne $CurId) {
        $DefaultPkg = (($Id.ToLowerInvariant() -split '\.') | ForEach-Object {
            $seg = ($_ -replace '[^a-z0-9_]', '_')
            if ([string]::IsNullOrEmpty($seg) -or $seg[0] -match '[0-9]') { "_$seg" } else { $seg }
        }) -join '.'
    }
    if ([string]::IsNullOrWhiteSpace($Package)) {
        $Package = if (-not $NonInteractive) { Read-PromptValue -Label 'Java package' -DefaultValue $DefaultPkg } else { $DefaultPkg }
    }
    if ($Package -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$') {
        throw "Invalid Java package '$Package'"
    }

    if ([string]::IsNullOrWhiteSpace($Author)) {
        $Author = if (-not $NonInteractive) { Read-PromptValue -Label 'Author name' -DefaultValue $CurAuthor } else { $CurAuthor }
    }

    if ([string]::IsNullOrWhiteSpace($RequiresCubism)) {
        $RequiresCubism = $CurRequiresCubism
    }
    if ($RequiresCubism -ne 'true' -and $RequiresCubism -ne 'false') {
        throw "--requires-cubism must be true or false (got '$RequiresCubism')"
    }

    if ([string]::IsNullOrWhiteSpace($ClassName)) {
        if ($Name -cne 'Hello Plugin' -or $Id -cne $CurId) {
            $words = [regex]::Matches($Name, '[A-Za-z0-9]+') | ForEach-Object { $_.Value }
            if (-not $words -or $words.Count -eq 0) {
                $words = [regex]::Matches(($Id -split '\.')[-1], '[A-Za-z0-9]+') | ForEach-Object { $_.Value }
            }
            $pascal = ($words | ForEach-Object { $_.Substring(0, 1).ToUpperInvariant() + $_.Substring(1) }) -join ''
            if ([string]::IsNullOrEmpty($pascal) -or $pascal[0] -match '[0-9]') { $pascal = "Plugin$pascal" }
            if (-not $pascal.EndsWith('Plugin')) { $pascal += 'Plugin' }
            $ClassName = $pascal
        } else {
            $ClassName = $CurClass
        }
    }

    $VersionsList = if (-not [string]::IsNullOrWhiteSpace($CubismVersions)) {
        @($CubismVersions -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    } elseif ($ResolvedCompatVersions.Count -gt 0) {
        $ResolvedCompatVersions
    } else {
        $DefaultReviewedVersions
    }
    foreach ($v in $VersionsList) {
        if ($v -notmatch '^5\.\d{1,2}\.\d{2}$') {
            throw "Invalid Cubism version: $v"
        }
    }

    $JavaRoot = Join-Path $ProjectRoot 'src/main/java'
    $OldJavaFile = Join-Path $JavaRoot (($CurPackage -replace '\.', '/') + "/$CurClass.java")
    $NewJavaFile = Join-Path $JavaRoot (($Package -replace '\.', '/') + "/$ClassName.java")
    if (-not (Test-Path -LiteralPath $OldJavaFile) -and (Test-Path -LiteralPath $NewJavaFile)) {
        $OldJavaFile = $NewJavaFile
    }
    if (-not (Test-Path -LiteralPath $OldJavaFile)) {
        throw "Entrypoint source file not found: $OldJavaFile"
    }

    $sourceText = [System.IO.File]::ReadAllText($OldJavaFile, $Utf8NoBom)
    $sourceText = [regex]::Replace($sourceText, '(?m)^package\s+[A-Za-z0-9_.]+\s*;', "package $Package;", 1)
    $sourceText = [regex]::Replace($sourceText, "\bpublic\s+class\s+$([regex]::Escape($CurClass))\b", "public class $ClassName", 1)
    $sourceText = $sourceText.Replace("`"$CurClass ", "`"$ClassName ")

    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($NewJavaFile))
    [System.IO.File]::WriteAllText($NewJavaFile, $sourceText, $Utf8NoBom)

    if ([System.IO.Path]::GetFullPath($OldJavaFile) -cne [System.IO.Path]::GetFullPath($NewJavaFile)) {
        Remove-Item -LiteralPath $OldJavaFile -Force
        $parent = [System.IO.Path]::GetDirectoryName($OldJavaFile)
        while ($parent -cne [System.IO.Path]::GetFullPath($JavaRoot) -and (Test-Path -LiteralPath $parent) -and (Get-ChildItem -LiteralPath $parent -Force).Count -eq 0) {
            Remove-Item -LiteralPath $parent -Force
            $parent = [System.IO.Path]::GetDirectoryName($parent)
        }
    }

    $CurrentMeta.id = $Id
    $CurrentMeta.name = $Name
    $CurrentMeta.entrypoints = @("$Package.$ClassName")
    $CurrentMeta.authors = @([pscustomobject]@{ name = $Author })
    if ($CurrentMeta.environment) {
        $CurrentMeta.environment.requiresCubism = ($RequiresCubism -eq 'true')
    }
    [System.IO.File]::WriteAllText($PluginJsonPath, (($CurrentMeta | ConvertTo-Json -Depth 10) + "`n"), $Utf8NoBom)

    $I18nDir = Join-Path $ProjectRoot 'src/main/resources/META-INF/turboism/i18n'
    if (Test-Path -LiteralPath $I18nDir) {
        Get-ChildItem -LiteralPath $I18nDir -Filter 'messages*.properties' | ForEach-Object {
            $propText = [System.IO.File]::ReadAllText($_.FullName, $Utf8NoBom)
            $propText = [regex]::Replace($propText, '(?m)^plugin\.name=.*$', "plugin.name=$Name")
            [System.IO.File]::WriteAllText($_.FullName, $propText, $Utf8NoBom)
        }
    }

    # Step 6: Write META-INF/turboism/cubism-versions.json
    $CubismVersionsPath = Join-Path $ProjectRoot 'src/main/resources/META-INF/turboism/cubism-versions.json'
    $CubismDoc = [ordered]@{
        format              = 'turboism.plugin-cubism-versions/v1'
        plugin              = $Id
        supportedVersions   = $VersionsList
        fullFeatureVersions = $VersionsList
    }
    [System.IO.File]::WriteAllText($CubismVersionsPath, (($CubismDoc | ConvertTo-Json -Depth 5) + "`n"), $Utf8NoBom)

    $slug = ($Id -split '\.')[-1]
    $archiveBase = if ($slug.EndsWith('-plugin')) { $slug } else { "$slug-turboism-plugin" }
    $pkgSegs = $Package -split '\.'
    $groupId = if ($pkgSegs.Count -gt 1) { ($pkgSegs[0..($pkgSegs.Count - 2)] -join '.') } else { $Package }

    $BuildKtsPath = Join-Path $ProjectRoot 'build.gradle.kts'
    if (Test-Path -LiteralPath $BuildKtsPath) {
        $buildKts = [System.IO.File]::ReadAllText($BuildKtsPath, $Utf8NoBom)
        $buildKts = [regex]::Replace($buildKts, '(?m)^group\s*=\s*"[^"]*"', "group = `"$groupId`"")
        $buildKts = [regex]::Replace($buildKts, 'archiveBaseName\.set\("[^"]*"\)', "archiveBaseName.set(`"$archiveBase`")")
        [System.IO.File]::WriteAllText($BuildKtsPath, $buildKts, $Utf8NoBom)
    }

    $SettingsKtsPath = Join-Path $ProjectRoot 'settings.gradle.kts'
    if (Test-Path -LiteralPath $SettingsKtsPath) {
        $settingsKts = [System.IO.File]::ReadAllText($SettingsKtsPath, $Utf8NoBom)
        $settingsKts = [regex]::Replace($settingsKts, '(?m)^rootProject\.name\s*=\s*"[^"]*"', "rootProject.name = `"$archiveBase`"")
        [System.IO.File]::WriteAllText($SettingsKtsPath, $settingsKts, $Utf8NoBom)
    }

    Write-Host "Configured plugin:"
    Write-Host "  ID:              $Id"
    Write-Host "  Name:            $Name"
    Write-Host "  Package:         $Package"
    Write-Host "  Author:          $Author"
    Write-Host "  SDK Version:     $ResolvedVersion"
    Write-Host "  Cubism Versions: $($VersionsList -join ',')"
    Write-Host "Setup complete. Run .\gradlew.bat build to compile and package your plugin."
} finally {
    if (Test-Path -LiteralPath $WorkTmp) {
        Remove-Item -LiteralPath $WorkTmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
