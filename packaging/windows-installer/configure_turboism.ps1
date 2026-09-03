# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [string]$HomePath = "",
    [switch]$Cleanup,
    [switch]$RetirePlugins,
    [switch]$IntegrateBat,
    [switch]$DisableBat,
    [switch]$EnableShortcuts,
    [switch]$DisableShortcuts,
    [switch]$InitializeSelection,
    [switch]$MigrateConfig,
    [switch]$Elevated,
    [string]$InstallerDiscoveryOutput = "",
    [switch]$InstallerDiscoveryWorker
)

# Turboism WinForms configurator: plugin selection, bounded Cubism selection,
# and explicit shortcut/BAT launch integration. Official BAT files are edited
# only through the separately selected, hash-guarded integration mode.
$ErrorActionPreference = "Stop"
$scriptPath = $MyInvocation.MyCommand.Path
$scriptDir = Split-Path -Parent $scriptPath
. (Join-Path $scriptDir "cubism-launch-common.ps1")

if (-not [string]::IsNullOrWhiteSpace($HomePath)) { $turboismHome = $HomePath.TrimEnd('\', '/') }
elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) { $turboismHome = $env:TURBOISM_HOME.TrimEnd('\', '/') }
else { $turboismHome = $scriptDir }
if (-not (Test-CubismNormalDirectory $turboismHome)) { throw "Turboism home does not exist: $turboismHome" }
if (-not [string]::IsNullOrWhiteSpace($InstallerDiscoveryOutput)) {
    if ($InstallerDiscoveryWorker) {
        try {
            Write-CubismInstallerDiscoveryReport `
                -TurboismHome $turboismHome `
                -OutputPath $InstallerDiscoveryOutput | Out-Null
            exit 0
        }
        catch {
            Write-Error $_.Exception.Message
            exit 1
        }
    }

    $discoveryProcess = $null
    try {
        $powershell = Join-Path $PSHOME "powershell.exe"
        if (-not (Test-CubismNormalFile $powershell)) { $powershell = (Get-Process -Id $PID).Path }
        $info = [System.Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $powershell
        $info.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" -Home "{1}" -InstallerDiscoveryOutput "{2}" -InstallerDiscoveryWorker' -f $scriptPath, $turboismHome, $InstallerDiscoveryOutput
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $discoveryProcess = [System.Diagnostics.Process]::new()
        $discoveryProcess.StartInfo = $info
        if (-not $discoveryProcess.Start()) { throw "Cannot start the Cubism discovery worker" }
        if (-not $discoveryProcess.WaitForExit(105000)) {
            try { $discoveryProcess.Kill() } catch { }
            try { $discoveryProcess.WaitForExit() } catch { }
            if (-not (Test-Path -LiteralPath $InstallerDiscoveryOutput)) {
                Write-CubismInstallerDiscoveryReport `
                    -TurboismHome $turboismHome `
                    -OutputPath $InstallerDiscoveryOutput `
                    -FailureCode "TimeoutException" | Out-Null
            }
            exit 1
        }
        if (-not (Test-CubismNormalFile $InstallerDiscoveryOutput)) {
            Write-CubismInstallerDiscoveryReport `
                -TurboismHome $turboismHome `
                -OutputPath $InstallerDiscoveryOutput `
                -FailureCode "MissingResult" | Out-Null
            exit 1
        }
        exit $discoveryProcess.ExitCode
    }
    catch {
        if (-not (Test-Path -LiteralPath $InstallerDiscoveryOutput)) {
            try {
                Write-CubismInstallerDiscoveryReport `
                    -TurboismHome $turboismHome `
                    -OutputPath $InstallerDiscoveryOutput `
                    -FailureCode $_.Exception.GetType().Name | Out-Null
            }
            catch { }
        }
        Write-Error $_.Exception.Message
        exit 1
    }
    finally {
        if ($null -ne $discoveryProcess) { $discoveryProcess.Dispose() }
    }
}
$statePath = Join-Path $turboismHome "cubism-installations.json"
$configPath = Join-Path $turboismHome "config.json"
$pluginDir = Join-Path $turboismHome "plugins"
$installerLogDir = Join-Path $turboismHome "logs\installer"
$installerLogPath = Join-Path $installerLogDir "configure-turboism.log"
try { New-Item -ItemType Directory -Path $installerLogDir -Force | Out-Null } catch { }
function Write-InstallerLog {
    param([string]$Event, [string]$Message = "")
    try {
        $safe = ($Message -replace '[\r\n]+', ' ').Trim()
        Add-Content -LiteralPath $installerLogPath -Encoding UTF8 -Value ("{0:o} {1} {2}" -f [DateTime]::UtcNow, $Event, $safe)
    }
    catch { }
}
Write-InstallerLog "CONFIGURATOR_START"

function Get-RuntimeConfigSchemaVersion {
    param([object]$Document)

    $property = $Document.PSObject.Properties["schemaVersion"]
    if ($null -eq $property -or $null -eq $property.Value) { return 0L }
    try {
        $number = [decimal]$property.Value
        if ($number -ne [decimal]::Truncate($number) -or $number -lt 0 -or $number -gt [long]::MaxValue) {
            throw "schemaVersion must be a non-negative integer"
        }
        return [long]$number
    }
    catch { throw "config.json schemaVersion is invalid" }
}

function Read-RuntimeConfigForMigration {
    if (-not (Test-CubismNormalFile $configPath)) { throw "config.json is not a normal file" }
    $bytes = [System.IO.File]::ReadAllBytes($configPath)
    if ($bytes.Length -gt 65536) { throw "config.json exceeds 64 KiB" }
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
        $text = $utf8.GetString($bytes)
        $document = $text | ConvertFrom-Json -ErrorAction Stop
    }
    catch { throw "config.json is not valid UTF-8 JSON" }
    if ($null -eq $document -or $document -isnot [pscustomobject]) {
        throw "config.json root must be an object"
    }
    return $document
}

function Convert-RuntimeConfigV0ToV1 {
    param([object]$Document)

    $allowed = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($name in @(
        "format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins",
        "logLevel", "maxLogStorageMiB", "locale", "safeMode", "diagnostics",
        "hooks", "launcher", "cubismJvm", "graalVmPath"
    )) { [void]$allowed.Add($name) }
    foreach ($property in $Document.PSObject.Properties) {
        if (-not $allowed.Contains($property.Name)) {
            throw "legacy config.json contains an unsupported field: $($property.Name)"
        }
    }

    $format = $Document.PSObject.Properties["format"]
    if ($null -ne $format -and $format.Value -ne "turboism.runtime.config") {
        throw "legacy config.json format is unsupported"
    }

    $migrated = [ordered]@{
        format = "turboism.runtime.config"
        schemaVersion = 1
        worktreeId = "turboism-runtime"
        pluginDirs = @("plugins")
    }
    foreach ($name in @(
        "worktreeId", "pluginDirs", "disabledPlugins", "logLevel", "maxLogStorageMiB",
        "locale", "safeMode", "diagnostics", "hooks"
    )) {
        $property = $Document.PSObject.Properties[$name]
        if ($null -ne $property) { $migrated[$name] = $property.Value }
    }

    $launcher = [ordered]@{ cubismJvm = "graalvm" }
    $launcherProperty = $Document.PSObject.Properties["launcher"]
    if ($null -ne $launcherProperty) {
        if ($null -eq $launcherProperty.Value -or $launcherProperty.Value -isnot [pscustomobject]) {
            throw "legacy config.json launcher must be an object"
        }
        foreach ($property in $launcherProperty.Value.PSObject.Properties) {
            if (@("cubismJvm", "graalVmPath") -cnotcontains $property.Name) {
                throw "legacy config.json contains an unsupported launcher field: $($property.Name)"
            }
            $launcher[$property.Name] = $property.Value
        }
    }
    foreach ($name in @("cubismJvm", "graalVmPath")) {
        $legacy = $Document.PSObject.Properties[$name]
        if ($null -ne $legacy) {
            if ($null -ne $launcherProperty -and $null -ne $launcherProperty.Value.PSObject.Properties[$name]) {
                throw "legacy config.json defines $name twice"
            }
            $launcher[$name] = $legacy.Value
        }
    }
    $migrated["launcher"] = $launcher
    return [pscustomobject]$migrated
}

function Write-RuntimeConfigMigrationAtomic {
    param([object]$Document)

    $json = $Document | ConvertTo-Json -Depth 16
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $bytes = $encoding.GetBytes($json + "`r`n")
    if ($bytes.Length -gt 65536) { throw "migrated config.json exceeds 64 KiB" }
    $temporary = Join-Path $turboismHome (".config-migrate-{0}-{1}.tmp" -f $PID, [guid]::NewGuid().ToString("N"))
    try {
        [System.IO.File]::WriteAllBytes($temporary, $bytes)
        if (-not (Test-CubismNormalFile $temporary)) { throw "temporary migrated config was not created" }
        [System.IO.File]::Replace($temporary, $configPath, $null, $true)
    }
    finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Invoke-RuntimeConfigMigration {
    if (-not (Test-Path -LiteralPath $configPath)) {
        Write-Host "TURBOISM_CONFIG absent"
        return
    }
    $document = Read-RuntimeConfigForMigration
    $schema = Get-RuntimeConfigSchemaVersion $document
    if ($schema -eq 1) {
        $format = $document.PSObject.Properties["format"]
        if ($null -eq $format -or $format.Value -ne "turboism.runtime.config") {
            throw "config.json format does not match schemaVersion 1"
        }
        Write-Host "TURBOISM_CONFIG unchanged schemaVersion=1"
        return
    }
    if ($schema -ne 0) { throw "no runtime config migration is available from schemaVersion $schema" }
    $migrated = Convert-RuntimeConfigV0ToV1 $document
    Write-RuntimeConfigMigrationAtomic $migrated
    Write-InstallerLog "CONFIG_MIGRATED" "from=0 to=1"
    Write-Host "TURBOISM_CONFIG migrated from=0 to=1"
}

function Test-CurrentProcessElevated {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    }
    catch { return $false }
}

function Invoke-ElevatedConfiguratorMode {
    param([string]$Mode)
    if (@("IntegrateBat", "DisableBat", "Cleanup") -notcontains $Mode) { throw "invalid elevated mode" }
    $powershell = Join-Path $PSHOME "powershell.exe"
    if (-not (Test-Path -LiteralPath $powershell -PathType Leaf)) { $powershell = (Get-Process -Id $PID).Path }
    $quotedScript = '"' + $scriptPath.Replace('"', '\"') + '"'
    $quotedHome = '"' + $turboismHome.Replace('"', '\"') + '"'
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File {0} -Home {1} -{2} -Elevated' -f $quotedScript, $quotedHome, $Mode
    $process = Start-Process -FilePath $powershell -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    return [int]$process.ExitCode
}

if (@(@($Cleanup, $RetirePlugins, $IntegrateBat, $DisableBat, $EnableShortcuts, $DisableShortcuts, $InitializeSelection, $MigrateConfig) | Where-Object { $_ }).Count -gt 1) {
    Write-Error "Cleanup, RetirePlugins, IntegrateBat, DisableBat, EnableShortcuts, DisableShortcuts, InitializeSelection, and MigrateConfig are mutually exclusive"
    exit 1
}
if ($MigrateConfig) {
    try {
        Invoke-RuntimeConfigMigration
        exit 0
    }
    catch {
        Write-InstallerLog "CONFIG_MIGRATION_FAILED" $_.Exception.Message
        Write-Error $_.Exception.Message
        exit 1
    }
}
if ($Cleanup) {
    try {
        if (Test-Path -LiteralPath $statePath) {
            $cleanupState = Read-CubismInstallationState -StatePath $statePath
            if (-not $cleanupState.Valid) { throw "Managed installation state is invalid: $($cleanupState.Error)" }
            if (-not $Elevated -and $cleanupState.BatIntegrations.Count -gt 0) {
                exit (Invoke-ElevatedConfiguratorMode -Mode "Cleanup")
            }
            Invoke-CubismManagedCleanup -TurboismHome $turboismHome -StatePath $statePath
        }
        else {
            Remove-CubismEmptyBackupDirectories -TurboismHome $turboismHome
        }
        exit 0
    }
    catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
if ($RetirePlugins) {
    try {
        Remove-TurboismRetiredPlugins -TurboismHome $turboismHome
        exit 0
    }
    catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
if ($InitializeSelection) {
    try {
        $state = Read-CubismInstallationState -StatePath $statePath
        if (-not $state.Valid) { throw "Managed installation state is invalid: $($state.Error)" }
        $roots = Get-CubismDiscoveryRoots -SavedRoots @($state.Installations | ForEach-Object { $_.Root })
        $candidates = @(Merge-CubismSelection -Candidates (Get-CubismInstallations -Roots $roots -TurboismHome $turboismHome) -SavedInstallations $state.Installations)
        Write-CubismInstallationState -StatePath $statePath -Candidates $candidates -ManagedShortcuts $state.ManagedShortcuts -ManagedShortcutHashes $state.ManagedShortcutHashes -ShortcutTakeovers $state.ShortcutTakeovers -BatIntegrations $state.BatIntegrations -LaunchMode $state.LaunchMode
        Write-Host "TURBOISM_CUBISM_SELECTION selected=$(@($candidates | Where-Object { $_.Selected -and $_.Selectable }).Count)"
        exit 0
    }
    catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
if ($DisableBat -and -not $Elevated) {
    try {
        $disableBatState = Read-CubismInstallationState -StatePath $statePath
        if (-not $disableBatState.Valid) { throw "Managed installation state is invalid: $($disableBatState.Error)" }
        if ($disableBatState.BatIntegrations.Count -eq 0) { exit 0 }
        exit (Invoke-ElevatedConfiguratorMode -Mode "DisableBat")
    }
    catch { Write-Error $_.Exception.Message; exit 1 }
}
if ($IntegrateBat -and -not $Elevated) {
    try { exit (Invoke-ElevatedConfiguratorMode -Mode "IntegrateBat") }
    catch { Write-Error $_.Exception.Message; exit 1 }
}
if ($IntegrateBat -or $DisableBat -or $EnableShortcuts -or $DisableShortcuts) {
    try {
        $state = Read-CubismInstallationState -StatePath $statePath
        if (-not $state.Valid) { throw "Managed installation state is invalid: $($state.Error)" }
        $candidates = @(Merge-CubismSelection -Candidates (Get-CubismInstallations -Roots (Get-CubismDiscoveryRoots -SavedRoots @($state.Installations | ForEach-Object { $_.Root })) -TurboismHome $turboismHome) -SavedInstallations $state.Installations)
        if ($IntegrateBat) {
            $selectedBatKeys = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
            foreach ($candidate in @($candidates | Where-Object { $_.Selected -and $_.Selectable })) {
                foreach ($path in @($candidate.OfficialBat, $candidate.D3DBat)) {
                    if (-not [string]::IsNullOrWhiteSpace($path)) { [void]$selectedBatKeys.Add([System.IO.Path]::GetFullPath($path)) }
                }
            }
            $retainedRecords = @($state.BatIntegrations | Where-Object { $selectedBatKeys.Contains([System.IO.Path]::GetFullPath($_.Path)) })
            $removedRecords = @($state.BatIntegrations | Where-Object { -not $selectedBatKeys.Contains([System.IO.Path]::GetFullPath($_.Path)) })
            if ($removedRecords.Count -gt 0) { Restore-CubismBatIntegrations -Records $removedRecords }
            $records = Invoke-CubismBatIntegration -TurboismHome $turboismHome -Candidates $candidates -ExistingRecords $retainedRecords
            Write-CubismInstallationState -StatePath $statePath -Candidates $candidates -ManagedShortcuts $state.ManagedShortcuts -ManagedShortcutHashes $state.ManagedShortcutHashes -ShortcutTakeovers $state.ShortcutTakeovers -BatIntegrations $records -LaunchMode $state.LaunchMode
            Write-Host "TURBOISM_BAT_INTEGRATION applied=$($records.Count) restored=$($removedRecords.Count)"
        }
        elseif ($DisableBat) {
            if ($state.BatIntegrations.Count -gt 0) { Restore-CubismBatIntegrations -Records $state.BatIntegrations }
            Write-CubismInstallationState -StatePath $statePath -Candidates $candidates -ManagedShortcuts $state.ManagedShortcuts -ManagedShortcutHashes $state.ManagedShortcutHashes -ShortcutTakeovers $state.ShortcutTakeovers -LaunchMode $state.LaunchMode
            Write-Host "TURBOISM_BAT_INTEGRATION restored=$($state.BatIntegrations.Count)"
        }
        elseif ($EnableShortcuts) {
            $launch = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $candidates -LaunchMode "independent" -ExistingState $state
            Write-Host "TURBOISM_SHORTCUT_INTEGRATION enabled=$($launch.ManagedShortcuts.Count)"
        }
        else {
            Disable-CubismShortcutIntegration -TurboismHome $turboismHome -StatePath $statePath -Candidates $candidates -ExistingState $state
            Write-Host "TURBOISM_SHORTCUT_INTEGRATION disabled"
        }
        exit 0
    }
    catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$uiLang = [System.Threading.Thread]::CurrentThread.CurrentUICulture.TwoLetterISOLanguageName
$uiStrings = @{
    en = @{
        FormTitle = "Turboism Configuration - {0}"; PluginsTab = "Plugins"; CubismTab = "Cubism installations"
        PluginPrompt = "Check the plugins to enable (unchecked ids are written to config.json):"
        CubismPrompt = "Select supported Cubism installations to manage and launch:"; Version = "Version"
        Ready = "Ready"; Invalid = "Invalid"; Unsupported = "Unsupported"; Selected = "selected"
        Rescan = "Rescan"; Add = "Add folder"; Remove = "Remove"; Save = "Save"; Cancel = "Cancel"
        LaunchMode = "Launch mode"; Independent = "Independent shortcuts (recommended)"; Takeover = "Take over existing Cubism shortcuts"
        ShortcutIntegration = "Create or update Turboism launch shortcuts for selected Cubism installations"
        BatIntegration = "Modify selected official Cubism BAT files (backed up and reversible)"
        NoActivation = "No activation path is selected. Turboism will not load from shortcuts or official BAT files. Continue?"
        IndependentHelp = "Creates new Turboism-owned .lnk shortcuts. Existing Cubism shortcuts and official BAT files remain byte-identical."
        TakeoverHelp = "Replaces only existing .lnk shortcuts whose target exactly matches a selected official Cubism BAT. Originals are backed up and restored on cleanup; the official BAT files themselves are never edited."
        StatusNoPlugins = "No valid plugin jars found under plugins/."; StatusSaved = "Saved configuration ({0} Cubism installation(s))."
        StatusNoCubism = "No supported Cubism installation was found. Turboism remains usable; add one later."
        StateWarning = "Managed installation state is invalid; repair it before saving: {0}"
        AddTitle = "Select a Cubism installation folder"; RemovePrompt = "Select an installation to remove first."
        Saved = "Configuration saved."; SaveError = "Could not save configuration: {0}"
        TakeoverSummary = "Eligible: {0}; unmatched fallback: {1}; conflicts: {2}"
        TakeoverUnavailable = "Takeover preview unavailable; save will fail closed: {0}"
        IndependentSummary = "Independent mode: existing Cubism shortcuts are untouched."
    }
    zh = @{
        FormTitle = "Turboism 配置 - {0}"; PluginsTab = "插件"; CubismTab = "Cubism 安装"
        PluginPrompt = "勾选要启用的插件（未勾选 id 将写入 config.json）："
        CubismPrompt = "选择要管理和启动的受支持 Cubism 安装："; Version = "版本"
        Ready = "就绪"; Invalid = "无效"; Unsupported = "不支持"; Selected = "已选择"
        Rescan = "重新扫描"; Add = "添加文件夹"; Remove = "移除"; Save = "保存"; Cancel = "取消"
        LaunchMode = "启动模式"; Independent = "独立快捷方式（推荐）"; Takeover = "接管现有 Cubism 快捷方式"
        ShortcutIntegration = "为所选 Cubism 安装创建或更新 Turboism 启动快捷方式"
        BatIntegration = "修改所选 Cubism 官方 BAT（自动备份且可恢复）"
        NoActivation = "没有选择任何激活路径。Turboism 将无法通过快捷方式或官方 BAT 加载。仍要继续吗？"
        IndependentHelp = "新建由 Turboism 管理的 .lnk 快捷方式；现有 Cubism 快捷方式和官方 BAT 文件保持字节不变。"
        TakeoverHelp = "仅替换目标精确匹配所选官方 Cubism BAT 的现有 .lnk 快捷方式；原快捷方式会备份并在清理时恢复，官方 BAT 文件本身始终不会被改写。"
        StatusNoPlugins = "plugins/ 下没有有效插件 jar。"; StatusSaved = "配置已保存（{0} 个 Cubism 安装）。"
        StatusNoCubism = "未找到受支持的 Cubism 安装。Turboism 仍可使用；稍后可添加。"
        StateWarning = "托管安装状态无效；修复后才能保存：{0}"; AddTitle = "选择 Cubism 安装文件夹"
        RemovePrompt = "请先选择要移除的安装。"; Saved = "配置已保存。"; SaveError = "无法保存配置：{0}"
        TakeoverSummary = "可接管：{0}；未匹配回退：{1}；冲突：{2}"; TakeoverUnavailable = "无法预览接管；保存将安全失败：{0}"
        IndependentSummary = "独立模式：不修改现有 Cubism 快捷方式。"
    }
    ja = @{
        FormTitle = "Turboism 設定 - {0}"; PluginsTab = "プラグイン"; CubismTab = "Cubism インストール"
        PluginPrompt = "有効にするプラグインを選択してください（未選択 id は config.json に書き込みます）："
        CubismPrompt = "管理して起動する対応 Cubism インストールを選択してください："; Version = "バージョン"
        Ready = "準備完了"; Invalid = "不正"; Unsupported = "未対応"; Selected = "選択済み"
        Rescan = "再スキャン"; Add = "フォルダーを追加"; Remove = "削除"; Save = "保存"; Cancel = "キャンセル"
        LaunchMode = "起動モード"; Independent = "独立ショートカット（推奨）"; Takeover = "既存 Cubism ショートカットを引き継ぐ"
        ShortcutIntegration = "選択した Cubism 用の Turboism 起動ショートカットを作成または更新"
        BatIntegration = "選択した Cubism 公式 BAT を変更（バックアップして復元可能）"
        NoActivation = "有効化経路が選択されていません。ショートカットまたは公式 BAT から Turboism は読み込まれません。続行しますか？"
        IndependentHelp = "Turboism 所有の新しい .lnk だけを作成し、既存 Cubism ショートカットと公式 BAT のバイト列は変更しません。"
        TakeoverHelp = "選択した公式 Cubism BAT を正確に指す既存 .lnk だけを置換し、元のショートカットをバックアップして復元します。公式 BAT 自体は編集しません。"
        StatusNoPlugins = "plugins/ に有効な plugin jar がありません。"; StatusSaved = "設定を保存しました（Cubism {0} 件）。"
        StatusNoCubism = "対応する Cubism インストールが見つかりません。Turboism は利用でき、後で追加できます。"
        StateWarning = "管理対象インストール状態が不正です。修復するまで保存できません：{0}"; AddTitle = "Cubism インストールフォルダーを選択"
        RemovePrompt = "先に削除するインストールを選択してください。"; Saved = "設定を保存しました。"; SaveError = "設定を保存できません：{0}"
        TakeoverSummary = "対象：{0}；未一致の回退：{1}；競合：{2}"; TakeoverUnavailable = "引き継ぎプレビューを取得できません。保存は失敗します：{0}"
        IndependentSummary = "独立モード：既存の Cubism ショートカットは変更しません。"
    }
}
if (-not $uiStrings.ContainsKey($uiLang)) { $uiLang = "en" }
$S = $uiStrings[$uiLang]

function Read-PluginMeta {
    param([string]$JarPath)
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            $entry = $zip.GetEntry("META-INF/turboism/plugin.json")
            if ($null -eq $entry) { return $null }
            $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
            try { return (($reader.ReadToEnd()) | ConvertFrom-Json) }
            finally { $reader.Dispose() }
        }
        finally { $zip.Dispose() }
    }
    catch { return $null }
}

$plugins = @()
if (Test-CubismNormalDirectory $pluginDir) {
    Get-ChildItem -LiteralPath $pluginDir -Filter *.jar -File | Sort-Object Name | ForEach-Object {
        $meta = Read-PluginMeta $_.FullName
        if ($null -ne $meta -and $meta.id) { $plugins += [pscustomobject]@{ Id = [string]$meta.id; Name = [string]$meta.name; Version = [string]$meta.version; Jar = $_.Name } }
    }
}
$existingConfig = $null
if (Test-CubismNormalFile $configPath) {
    try { $existingConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $existingConfig = $null }
}
$existingDisabled = @()
if ($null -ne $existingConfig -and $null -ne $existingConfig.disabledPlugins) { $existingDisabled = @($existingConfig.disabledPlugins) }

$state = Read-CubismInstallationState -StatePath $statePath
$stateInstallations = @($state.Installations)
$manualRoots = @()
$script:automaticRootKeys = @()
function Refresh-CubismCandidates {
    $savedRoots = @($stateInstallations | ForEach-Object { $_.Root })
    $discovery = Get-CubismDiscoveryRoots -SavedRoots $savedRoots -ManualRoots $manualRoots -IncludeMetadata
    $script:automaticRootKeys = @($discovery.AutomaticRootKeys)
    return @(Merge-CubismSelection -Candidates (Get-CubismInstallations -Roots $discovery.Roots -TurboismHome $turboismHome) -SavedInstallations $stateInstallations)
}
$candidates = Refresh-CubismCandidates

[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$formIcon = $null
$form.Text = $S.FormTitle -f $turboismHome
$form.ClientSize = New-Object System.Drawing.Size(1080, 900)
$form.MinimumSize = New-Object System.Drawing.Size(900, 720)
$form.StartPosition = "CenterScreen"; $form.MinimizeBox = $true; $form.MaximizeBox = $true
$iconPath = Join-Path $turboismHome "turboism.ico"
if (Test-CubismNormalFile $iconPath) {
    try {
        $iconStream = [System.IO.File]::Open($iconPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
        try {
            $loadedIcon = New-Object System.Drawing.Icon($iconStream)
            try { $formIcon = $loadedIcon.Clone(); $form.Icon = $formIcon }
            finally { $loadedIcon.Dispose() }
        }
        finally { $iconStream.Dispose() }
    }
    catch { Write-InstallerLog "CONFIGURATOR_ICON_FAILED" $_.Exception.Message }
}
$tabs = New-Object System.Windows.Forms.TabControl
$tabs.Location = New-Object System.Drawing.Point(8, 8); $tabs.Size = New-Object System.Drawing.Size(1064, 800); $tabs.Anchor = 'Top, Bottom, Left, Right'
$pluginPage = New-Object System.Windows.Forms.TabPage; $pluginPage.Text = $S.PluginsTab
$cubismPage = New-Object System.Windows.Forms.TabPage; $cubismPage.Text = $S.CubismTab
[void]$tabs.TabPages.Add($pluginPage); [void]$tabs.TabPages.Add($cubismPage); $form.Controls.Add($tabs)

$pluginLabel = New-Object System.Windows.Forms.Label; $pluginLabel.Text = $S.PluginPrompt
$pluginLabel.Location = New-Object System.Drawing.Point(12, 12); $pluginLabel.AutoSize = $true; $pluginPage.Controls.Add($pluginLabel)
$pluginList = New-Object System.Windows.Forms.CheckedListBox
$pluginList.Location = New-Object System.Drawing.Point(12, 38); $pluginList.Size = New-Object System.Drawing.Size(1025, 700); $pluginList.CheckOnClick = $true; $pluginList.Anchor = 'Top, Bottom, Left, Right'
foreach ($plugin in $plugins) {
    $text = "{0}  [{1}]  v{2}" -f $plugin.Name, $plugin.Id, $plugin.Version
    [void]$pluginList.Items.Add($text, ($existingDisabled -notcontains $plugin.Id))
}
$pluginPage.Controls.Add($pluginList)

$cubismLabel = New-Object System.Windows.Forms.Label; $cubismLabel.Text = $S.CubismPrompt
$cubismLabel.Location = New-Object System.Drawing.Point(12, 12); $cubismLabel.AutoSize = $true; $cubismPage.Controls.Add($cubismLabel)
$cubismList = New-Object System.Windows.Forms.CheckedListBox
$cubismList.Location = New-Object System.Drawing.Point(12, 38); $cubismList.Size = New-Object System.Drawing.Size(1025, 555); $cubismList.CheckOnClick = $true; $cubismList.HorizontalScrollbar = $true; $cubismList.Anchor = 'Top, Bottom, Left, Right'
$cubismPage.Controls.Add($cubismList)
$shortcutCheck = New-Object System.Windows.Forms.CheckBox; $shortcutCheck.Text = $S.ShortcutIntegration; $shortcutCheck.Location = New-Object System.Drawing.Point(12, 600); $shortcutCheck.Size = New-Object System.Drawing.Size(1025, 24); $shortcutCheck.Anchor = 'Bottom, Left, Right'; $shortcutCheck.Checked = [bool]$InitialShortcuts -or @($state.ManagedShortcuts).Count -gt 0 -or @($state.ShortcutTakeovers).Count -gt 0; $cubismPage.Controls.Add($shortcutCheck)
$modeLabel = New-Object System.Windows.Forms.Label; $modeLabel.Text = $S.LaunchMode; $modeLabel.Location = New-Object System.Drawing.Point(32, 632); $modeLabel.AutoSize = $true; $modeLabel.Anchor = 'Bottom, Left'; $cubismPage.Controls.Add($modeLabel)
$modeBox = New-Object System.Windows.Forms.ComboBox; $modeBox.DropDownStyle = "DropDownList"; $modeBox.Location = New-Object System.Drawing.Point(135, 628); $modeBox.Size = New-Object System.Drawing.Size(902, 24); $modeBox.Anchor = 'Bottom, Left, Right'
[void]$modeBox.Items.Add($S.Independent); [void]$modeBox.Items.Add($S.Takeover); $modeBox.SelectedIndex = if ($state.LaunchMode -eq "takeover") { 1 } else { 0 }; $cubismPage.Controls.Add($modeBox)
$modeHelp = New-Object System.Windows.Forms.Label; $modeHelp.Location = New-Object System.Drawing.Point(32, 658); $modeHelp.Size = New-Object System.Drawing.Size(1005, 42); $modeHelp.Anchor = 'Bottom, Left, Right'; $cubismPage.Controls.Add($modeHelp)
$batCheck = New-Object System.Windows.Forms.CheckBox; $batCheck.Text = $S.BatIntegration; $batCheck.Location = New-Object System.Drawing.Point(12, 704); $batCheck.Size = New-Object System.Drawing.Size(1025, 24); $batCheck.Anchor = 'Bottom, Left, Right'; $batCheck.Checked = [bool]$InitialBat -or @($state.BatIntegrations).Count -gt 0; $cubismPage.Controls.Add($batCheck)
$cubismStatus = New-Object System.Windows.Forms.Label; $cubismStatus.Location = New-Object System.Drawing.Point(12, 730); $cubismStatus.AutoSize = $true; $cubismStatus.Anchor = 'Bottom, Left'; $cubismPage.Controls.Add($cubismStatus)
$rescanButton = New-Object System.Windows.Forms.Button; $rescanButton.Text = $S.Rescan; $rescanButton.Location = New-Object System.Drawing.Point(12, 752); $rescanButton.Size = New-Object System.Drawing.Size(90, 30); $rescanButton.Anchor = 'Bottom, Left'; $cubismPage.Controls.Add($rescanButton)
$addButton = New-Object System.Windows.Forms.Button; $addButton.Text = $S.Add; $addButton.Location = New-Object System.Drawing.Point(110, 752); $addButton.Size = New-Object System.Drawing.Size(110, 30); $addButton.Anchor = 'Bottom, Left'; $cubismPage.Controls.Add($addButton)
$removeButton = New-Object System.Windows.Forms.Button; $removeButton.Text = $S.Remove; $removeButton.Location = New-Object System.Drawing.Point(228, 752); $removeButton.Size = New-Object System.Drawing.Size(90, 30); $removeButton.Anchor = 'Bottom, Left'; $cubismPage.Controls.Add($removeButton)
$statusLabel = New-Object System.Windows.Forms.Label; $statusLabel.Location = New-Object System.Drawing.Point(12, 830); $statusLabel.AutoSize = $true; $statusLabel.Anchor = 'Bottom, Left'; $form.Controls.Add($statusLabel)
$saveButton = New-Object System.Windows.Forms.Button; $saveButton.Text = $S.Save; $saveButton.Location = New-Object System.Drawing.Point(880, 850); $saveButton.Size = New-Object System.Drawing.Size(90, 30); $saveButton.Anchor = 'Bottom, Right'; $form.Controls.Add($saveButton)
$cancelButton = New-Object System.Windows.Forms.Button; $cancelButton.Text = $S.Cancel; $cancelButton.Location = New-Object System.Drawing.Point(980, 850); $cancelButton.Size = New-Object System.Drawing.Size(90, 30); $cancelButton.Anchor = 'Bottom, Right'; $cancelButton.Add_Click({ $form.Close() }); $form.Controls.Add($cancelButton)

function Update-LaunchModeSummary {
    if ($modeBox.SelectedIndex -eq 0) { $modeHelp.Text = $S.IndependentHelp; $cubismStatus.Text = $S.IndependentSummary; return }
    $modeHelp.Text = $S.TakeoverHelp
    try {
        $preview = Get-CubismTakeoverPreview -Candidates $candidates -State $state
        $cubismStatus.Text = $S.TakeoverSummary -f $preview.Eligible.Count, $preview.Unmatched.Count, $preview.Conflicted.Count
    }
    catch { $cubismStatus.Text = $S.TakeoverUnavailable -f $_.Exception.Message }
}
function Render-CubismCandidates {
    $cubismList.Items.Clear()
    foreach ($candidate in @($candidates)) {
        $stateText = if ($candidate.Selectable) { $S.Ready } elseif ($candidate.Status -eq "Unsupported") { $S.Unsupported } else { $S.Invalid }
        $text = "{0}  |  {1}  |  {2}  |  {3}" -f $(if ($candidate.Version) { $candidate.Version } else { "?" }), $stateText, $candidate.CanonicalRoot, $candidate.Reason
        [void]$cubismList.Items.Add($text, ([bool]$candidate.Selected -and [bool]$candidate.Selectable))
    }
    $selected = @($candidates | Where-Object { $_.Selectable -and $_.Selected }).Count
    if ($modeBox.SelectedIndex -eq 0) { $cubismStatus.Text = "$selected $($S.Selected)" }
    if ($selected -eq 0 -and @($candidates | Where-Object { $_.Selectable }).Count -eq 0) { $statusLabel.Text = $S.StatusNoCubism }
    Update-LaunchModeSummary
}
Render-CubismCandidates
if (-not $state.Valid) { $statusLabel.Text = $S.StateWarning -f $state.Error }
if ($plugins.Count -eq 0) { $statusLabel.Text = $S.StatusNoPlugins }
$modeBox.Enabled = [bool]$shortcutCheck.Checked
$modeHelp.Enabled = [bool]$shortcutCheck.Checked
$shortcutCheck.Add_CheckedChanged({
    $modeBox.Enabled = [bool]$shortcutCheck.Checked
    $modeHelp.Enabled = [bool]$shortcutCheck.Checked
})
$modeBox.Add_SelectedIndexChanged({ Update-LaunchModeSummary })
$rescanButton.Add_Click({
    for ($i = 0; $i -lt $candidates.Count; $i++) { $candidates[$i].Selected = $cubismList.GetItemChecked($i) }
    $stateInstallations = @($candidates | ForEach-Object { [pscustomobject]@{ Root = $_.CanonicalRoot; Version = $_.Version; Selected = $_.Selected } })
    $candidates = Refresh-CubismCandidates; Render-CubismCandidates
})
$addButton.Add_Click({
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog; $dialog.Description = $S.AddTitle
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $candidate = New-CubismInstallationCandidate -Root $dialog.SelectedPath -Source "manual" -TurboismHome $turboismHome
        if ($null -ne $candidate.Key -and @($candidates | Where-Object { $_.Key -eq $candidate.Key }).Count -eq 0) {
            $manualRoots += $candidate.CanonicalRoot; $candidate.Selected = [bool]$candidate.Selectable; $candidates += $candidate
            $candidates = @($candidates | Sort-Object @{Expression={ if ($_.Version) { [version]$_.Version } else { [version]"0.0.0" } }}, @{Expression={ $_.CanonicalRoot.ToUpperInvariant() }})
            Render-CubismCandidates
        }
    }
    $dialog.Dispose()
})
$removeButton.Add_Click({
    if ($cubismList.SelectedIndices.Count -eq 0) { $statusLabel.Text = $S.RemovePrompt; return }
    $removeKeys = @($cubismList.SelectedIndices | ForEach-Object { $candidates[$_].Key })
    $removed = Remove-CubismCandidateEntries -Candidates $candidates -RemoveKeys $removeKeys -StateInstallations $stateInstallations -ManualRoots $manualRoots -AutomaticRootKeys $script:automaticRootKeys
    $candidates = @($removed.Candidates); $stateInstallations = @($removed.StateInstallations); $manualRoots = @($removed.ManualRoots); Render-CubismCandidates
})

$saveButton.Add_Click({
    try {
        for ($i = 0; $i -lt $candidates.Count; $i++) { $candidates[$i].Selected = [bool]$cubismList.GetItemChecked($i) -and [bool]$candidates[$i].Selectable }
        $unchecked = @()
        for ($i = 0; $i -lt $plugins.Count; $i++) { if (-not $pluginList.GetItemChecked($i)) { $unchecked += $plugins[$i].Id } }
        $known = @($plugins | ForEach-Object { $_.Id })
        foreach ($id in $existingDisabled) { if (($known -notcontains $id) -and ($unchecked -notcontains $id)) { $unchecked += $id } }
        $unchecked = @($unchecked | Sort-Object -Unique)
        $mode = if ($modeBox.SelectedIndex -eq 1) { "takeover" } else { "independent" }
        $selectedCount = @($candidates | Where-Object { $_.Selected -and $_.Selectable }).Count
        if (-not $shortcutCheck.Checked -and -not $batCheck.Checked) {
            $choice = [System.Windows.Forms.MessageBox]::Show($S.NoActivation, $form.Text, [System.Windows.Forms.MessageBoxButtons]::YesNo, [System.Windows.Forms.MessageBoxIcon]::Warning)
            if ($choice -ne [System.Windows.Forms.DialogResult]::Yes) { return }
        }
        Write-InstallerLog "SELECTION_SAVE" ("selected={0} shortcuts={1} mode={2} bat={3}" -f $selectedCount, [bool]$shortcutCheck.Checked, $mode, [bool]$batCheck.Checked)
        if ($shortcutCheck.Checked) {
            $launch = Invoke-CubismLaunchConfiguration -TurboismHome $turboismHome -StatePath $statePath -Candidates $candidates -LaunchMode $mode -ExistingState $state
        }
        else {
            Disable-CubismShortcutIntegration -TurboismHome $turboismHome -StatePath $statePath -Candidates $candidates -ExistingState $state
            $launch = [pscustomobject]@{ Eligible = @(); Unmatched = @(); Conflicted = @() }
        }
        $postLaunchState = Read-CubismInstallationState -StatePath $statePath
        if (-not $postLaunchState.Valid) { throw "Managed installation state is invalid after shortcut configuration: $($postLaunchState.Error)" }
        if ($batCheck.Checked) {
            $batExit = Invoke-ElevatedConfiguratorMode -Mode "IntegrateBat"
            if ($batExit -ne 0) { throw "Elevated Cubism BAT integration failed with exit code $batExit." }
            $postLaunchState = Read-CubismInstallationState -StatePath $statePath
            Write-InstallerLog "BAT_INTEGRATION_OK" ("records={0}" -f $postLaunchState.BatIntegrations.Count)
        }
        elseif ($postLaunchState.BatIntegrations.Count -gt 0) {
            $batExit = Invoke-ElevatedConfiguratorMode -Mode "DisableBat"
            if ($batExit -ne 0) { throw "Elevated Cubism BAT restoration failed with exit code $batExit." }
            $postLaunchState = Read-CubismInstallationState -StatePath $statePath
            Write-InstallerLog "BAT_INTEGRATION_RESTORED"
        }

        $config = [ordered]@{ format = "turboism.runtime.config"; schemaVersion = 1; worktreeId = "turboism-runtime"; pluginDirs = @("plugins") }
        if ($null -ne $existingConfig) {
            foreach ($property in $existingConfig.PSObject.Properties) {
                if (@("format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins") -notcontains $property.Name) { $config[$property.Name] = $property.Value }
            }
        }
        if ($unchecked.Count -gt 0) { $config.disabledPlugins = $unchecked } else { $config.Remove("disabledPlugins") }
        $json = $config | ConvertTo-Json -Depth 8
        $json = $json -replace '"disabledPlugins":\s*"([^"]+)"', '"disabledPlugins": ["$1"]'
        # 无 BOM UTF-8 写入（PowerShell 5.1 的 Set-Content -Encoding UTF8 会写 BOM，
        # 导致 Java 安装器 BoundedJson.parse 拒绝 config.json）。UTF8Encoding(false) 为 PS 5.1 兼容。
        [System.IO.File]::WriteAllText($configPath, $json, [System.Text.UTF8Encoding]::new($false))
        $state = Read-CubismInstallationState -StatePath $statePath
        Write-InstallerLog "CONFIGURATION_SAVED" ("selected={0} shortcuts={1} mode={2} bat={3}" -f $selectedCount, [bool]$shortcutCheck.Checked, $mode, [bool]$batCheck.Checked)
        if ($mode -eq "takeover") {
            $statusLabel.Text = $S.StatusSaved -f $selectedCount
            $cubismStatus.Text = $S.TakeoverSummary -f $launch.Eligible.Count, $launch.Unmatched.Count, $launch.Conflicted.Count
        }
        else { $statusLabel.Text = $S.StatusSaved -f $selectedCount }
        [System.Windows.Forms.MessageBox]::Show($S.Saved, $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
        $form.Close()
    }
    catch {
        Write-InstallerLog "CONFIGURATION_FAILED" $_.Exception.Message
        $message = (($S.SaveError -f $_.Exception.Message) + "`r`n`r`nLog: " + $installerLogPath)
        [System.Windows.Forms.MessageBox]::Show($message, $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
})
try { [void]$form.ShowDialog() }
finally {
    if ($null -ne $formIcon) { $formIcon.Dispose() }
    $form.Dispose()
}
