# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [string]$Home = "",
    [switch]$Cleanup
)

# Turboism WinForms configurator: plugin selection, bounded Cubism selection,
# and the explicit independent/takeover launch policy. It never edits Cubism.
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")

if (-not [string]::IsNullOrWhiteSpace($Home)) { $turboismHome = $Home.TrimEnd('\', '/') }
elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) { $turboismHome = $env:TURBOISM_HOME.TrimEnd('\', '/') }
else { $turboismHome = $scriptDir }
if (-not (Test-CubismNormalDirectory $turboismHome)) { throw "Turboism home does not exist: $turboismHome" }
$statePath = Join-Path $turboismHome "cubism-installations.json"
$configPath = Join-Path $turboismHome "config.json"
$pluginDir = Join-Path $turboismHome "plugins"

if ($Cleanup) {
    try {
        Invoke-CubismManagedCleanup -Home $turboismHome -StatePath $statePath
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
        IndependentHelp = "Creates Turboism-owned shortcuts and never changes existing shortcuts."
        TakeoverHelp = "Replaces only shortcuts whose target is an exact selected official Cubism BAT; originals are backed up and restored on cleanup."
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
        IndependentHelp = "仅创建 Turboism 所有的快捷方式，不修改现有快捷方式。"
        TakeoverHelp = "仅替换目标精确匹配所选官方 Cubism BAT 的快捷方式；原文件备份并在清理时恢复。"
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
        IndependentHelp = "Turboism 所有のショートカットだけを作成し、既存のショートカットは変更しません。"
        TakeoverHelp = "選択した公式 Cubism BAT への完全一致だけを置換し、元のファイルをバックアップして復元します。"
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
    return @(Merge-CubismSelection -Candidates (Get-CubismInstallations -Roots $discovery.Roots) -SavedInstallations $stateInstallations)
}
$candidates = Refresh-CubismCandidates

[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = $S.FormTitle -f $turboismHome
$form.Size = New-Object System.Drawing.Size(720, 640)
$form.StartPosition = "CenterScreen"; $form.MinimizeBox = $false; $form.MaximizeBox = $false
$tabs = New-Object System.Windows.Forms.TabControl
$tabs.Location = New-Object System.Drawing.Point(8, 8); $tabs.Size = New-Object System.Drawing.Size(688, 520)
$pluginPage = New-Object System.Windows.Forms.TabPage; $pluginPage.Text = $S.PluginsTab
$cubismPage = New-Object System.Windows.Forms.TabPage; $cubismPage.Text = $S.CubismTab
[void]$tabs.TabPages.Add($pluginPage); [void]$tabs.TabPages.Add($cubismPage); $form.Controls.Add($tabs)

$pluginLabel = New-Object System.Windows.Forms.Label; $pluginLabel.Text = $S.PluginPrompt
$pluginLabel.Location = New-Object System.Drawing.Point(12, 12); $pluginLabel.AutoSize = $true; $pluginPage.Controls.Add($pluginLabel)
$pluginList = New-Object System.Windows.Forms.CheckedListBox
$pluginList.Location = New-Object System.Drawing.Point(12, 38); $pluginList.Size = New-Object System.Drawing.Size(650, 420); $pluginList.CheckOnClick = $true
foreach ($plugin in $plugins) {
    $text = "{0}  [{1}]  v{2}" -f $plugin.Name, $plugin.Id, $plugin.Version
    [void]$pluginList.Items.Add($text, ($existingDisabled -notcontains $plugin.Id))
}
$pluginPage.Controls.Add($pluginList)

$cubismLabel = New-Object System.Windows.Forms.Label; $cubismLabel.Text = $S.CubismPrompt
$cubismLabel.Location = New-Object System.Drawing.Point(12, 12); $cubismLabel.AutoSize = $true; $cubismPage.Controls.Add($cubismLabel)
$cubismList = New-Object System.Windows.Forms.CheckedListBox
$cubismList.Location = New-Object System.Drawing.Point(12, 38); $cubismList.Size = New-Object System.Drawing.Size(650, 285); $cubismList.CheckOnClick = $true; $cubismList.HorizontalScrollbar = $true
$cubismPage.Controls.Add($cubismList)
$modeLabel = New-Object System.Windows.Forms.Label; $modeLabel.Text = $S.LaunchMode; $modeLabel.Location = New-Object System.Drawing.Point(12, 335); $modeLabel.AutoSize = $true; $cubismPage.Controls.Add($modeLabel)
$modeBox = New-Object System.Windows.Forms.ComboBox; $modeBox.DropDownStyle = "DropDownList"; $modeBox.Location = New-Object System.Drawing.Point(115, 331); $modeBox.Size = New-Object System.Drawing.Size(535, 24)
[void]$modeBox.Items.Add($S.Independent); [void]$modeBox.Items.Add($S.Takeover); $modeBox.SelectedIndex = if ($state.LaunchMode -eq "takeover") { 1 } else { 0 }; $cubismPage.Controls.Add($modeBox)
$modeHelp = New-Object System.Windows.Forms.Label; $modeHelp.Location = New-Object System.Drawing.Point(12, 360); $modeHelp.Size = New-Object System.Drawing.Size(650, 36); $cubismPage.Controls.Add($modeHelp)
$cubismStatus = New-Object System.Windows.Forms.Label; $cubismStatus.Location = New-Object System.Drawing.Point(12, 402); $cubismStatus.AutoSize = $true; $cubismPage.Controls.Add($cubismStatus)
$rescanButton = New-Object System.Windows.Forms.Button; $rescanButton.Text = $S.Rescan; $rescanButton.Location = New-Object System.Drawing.Point(12, 440); $rescanButton.Size = New-Object System.Drawing.Size(90, 30); $cubismPage.Controls.Add($rescanButton)
$addButton = New-Object System.Windows.Forms.Button; $addButton.Text = $S.Add; $addButton.Location = New-Object System.Drawing.Point(110, 440); $addButton.Size = New-Object System.Drawing.Size(110, 30); $cubismPage.Controls.Add($addButton)
$removeButton = New-Object System.Windows.Forms.Button; $removeButton.Text = $S.Remove; $removeButton.Location = New-Object System.Drawing.Point(228, 440); $removeButton.Size = New-Object System.Drawing.Size(90, 30); $cubismPage.Controls.Add($removeButton)
$statusLabel = New-Object System.Windows.Forms.Label; $statusLabel.Location = New-Object System.Drawing.Point(12, 548); $statusLabel.AutoSize = $true; $form.Controls.Add($statusLabel)
$saveButton = New-Object System.Windows.Forms.Button; $saveButton.Text = $S.Save; $saveButton.Location = New-Object System.Drawing.Point(500, 560); $saveButton.Size = New-Object System.Drawing.Size(90, 30); $form.Controls.Add($saveButton)
$cancelButton = New-Object System.Windows.Forms.Button; $cancelButton.Text = $S.Cancel; $cancelButton.Location = New-Object System.Drawing.Point(600, 560); $cancelButton.Size = New-Object System.Drawing.Size(90, 30); $cancelButton.Add_Click({ $form.Close() }); $form.Controls.Add($cancelButton)

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
$modeBox.Add_SelectedIndexChanged({ Update-LaunchModeSummary })
$rescanButton.Add_Click({
    for ($i = 0; $i -lt $candidates.Count; $i++) { $candidates[$i].Selected = $cubismList.GetItemChecked($i) }
    $stateInstallations = @($candidates | ForEach-Object { [pscustomobject]@{ Root = $_.CanonicalRoot; Version = $_.Version; Selected = $_.Selected } })
    $candidates = Refresh-CubismCandidates; Render-CubismCandidates
})
$addButton.Add_Click({
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog; $dialog.Description = $S.AddTitle
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $candidate = New-CubismInstallationCandidate -Root $dialog.SelectedPath -Source "manual"
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
        $launch = Invoke-CubismLaunchConfiguration -Home $turboismHome -StatePath $statePath -Candidates $candidates -LaunchMode $mode -ExistingState $state

        $config = [ordered]@{ format = "turboism.runtime.config"; schemaVersion = 1; worktreeId = "turboism-runtime"; pluginDirs = @("plugins") }
        if ($null -ne $existingConfig) {
            foreach ($property in $existingConfig.PSObject.Properties) {
                if (@("format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins") -notcontains $property.Name) { $config[$property.Name] = $property.Value }
            }
        }
        if ($unchecked.Count -gt 0) { $config.disabledPlugins = $unchecked } else { $config.Remove("disabledPlugins") }
        $json = $config | ConvertTo-Json -Depth 8
        $json = $json -replace '"disabledPlugins":\s*"([^"]+)"', '"disabledPlugins": ["$1"]'
        Set-Content -LiteralPath $configPath -Value $json -Encoding UTF8
        $state = Read-CubismInstallationState -StatePath $statePath
        $selectedCount = @($candidates | Where-Object { $_.Selected -and $_.Selectable }).Count
        if ($mode -eq "takeover") {
            $statusLabel.Text = $S.StatusSaved -f $selectedCount
            $cubismStatus.Text = $S.TakeoverSummary -f $launch.Eligible.Count, $launch.Unmatched.Count, $launch.Conflicted.Count
        }
        else { $statusLabel.Text = $S.StatusSaved -f $selectedCount }
        [System.Windows.Forms.MessageBox]::Show($S.Saved, $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
        $form.Close()
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(($S.SaveError -f $_.Exception.Message), $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
})
[void]$form.ShowDialog()
