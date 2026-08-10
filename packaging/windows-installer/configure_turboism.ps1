# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [string]$Home = "",
    [switch]$Cleanup
)

# Turboism WinForms configurator: plugin selection plus bounded managed Cubism
# installation selection. It never edits or copies a Cubism-owned file.
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")

if (-not [string]::IsNullOrWhiteSpace($Home)) { $turboismHome = $Home.TrimEnd('\', '/') }
elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) { $turboismHome = $env:TURBOISM_HOME.TrimEnd('\', '/') }
else { $turboismHome = $scriptDir }
if (-not (Test-Path -LiteralPath $turboismHome -PathType Container)) { throw "Turboism home does not exist: $turboismHome" }
$statePath = Join-Path $turboismHome "cubism-installations.json"
$configPath = Join-Path $turboismHome "config.json"
$pluginDir = Join-Path $turboismHome "plugins"

if ($Cleanup) {
    $state = Read-CubismInstallationState -StatePath $statePath
    if (-not $state.Valid) { throw "Refusing shortcut cleanup because managed state is invalid: $($state.Error)" }
    $failed = @(Remove-CubismManagedShortcuts -Paths $state.ManagedShortcuts)
    if ($failed.Count -gt 0) { throw "Managed shortcut cleanup is incomplete; state was preserved for retry." }
    if (Test-Path -LiteralPath $statePath -PathType Leaf) { Remove-Item -LiteralPath $statePath -Force -ErrorAction Stop }
    exit 0
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
        StatusNoPlugins = "No valid plugin jars found under plugins/."; StatusSaved = "Saved configuration ({0} Cubism installation(s))."
        StatusNoCubism = "No supported Cubism installation was found. Turboism remains usable; add one later."
        StateWarning = "Managed installation state was invalid and will be replaced when saved: {0}"
        AddTitle = "Select a Cubism installation folder"; RemovePrompt = "Select an installation to remove first."
        Saved = "Configuration saved."; SaveError = "Could not save configuration: {0}"
    }
    zh = @{
        FormTitle = "Turboism 配置 - {0}"; PluginsTab = "插件"; CubismTab = "Cubism 安装"
        PluginPrompt = "勾选要启用的插件（未勾选 id 将写入 config.json）："
        CubismPrompt = "选择要管理和启动的受支持 Cubism 安装："; Version = "版本"
        Ready = "就绪"; Invalid = "无效"; Unsupported = "不支持"; Selected = "已选择"
        Rescan = "重新扫描"; Add = "添加文件夹"; Remove = "移除"; Save = "保存"; Cancel = "取消"
        StatusNoPlugins = "plugins/ 下没有有效插件 jar。"; StatusSaved = "配置已保存（{0} 个 Cubism 安装）。"
        StatusNoCubism = "未找到受支持的 Cubism 安装。Turboism 仍可使用；稍后可添加。"
        StateWarning = "托管安装状态无效，保存时将替换：{0}"; AddTitle = "选择 Cubism 安装文件夹"
        RemovePrompt = "请先选择要移除的安装。"; Saved = "配置已保存。"; SaveError = "无法保存配置：{0}"
    }
    ja = @{
        FormTitle = "Turboism 設定 - {0}"; PluginsTab = "プラグイン"; CubismTab = "Cubism インストール"
        PluginPrompt = "有効にするプラグインを選択してください（未選択 id は config.json に書き込みます）："
        CubismPrompt = "管理して起動する対応 Cubism インストールを選択してください："; Version = "バージョン"
        Ready = "準備完了"; Invalid = "不正"; Unsupported = "未対応"; Selected = "選択済み"
        Rescan = "再スキャン"; Add = "フォルダーを追加"; Remove = "削除"; Save = "保存"; Cancel = "キャンセル"
        StatusNoPlugins = "plugins/ に有効な plugin jar がありません。"; StatusSaved = "設定を保存しました（Cubism {0} 件）。"
        StatusNoCubism = "対応する Cubism インストールが見つかりません。Turboism は利用でき、後で追加できます。"
        StateWarning = "管理対象インストール状態が不正です。保存時に置き換えます：{0}"; AddTitle = "Cubism インストールフォルダーを選択"
        RemovePrompt = "先に削除するインストールを選択してください。"; Saved = "設定を保存しました。"; SaveError = "設定を保存できません：{0}"
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
if (Test-Path -LiteralPath $pluginDir -PathType Container) {
    Get-ChildItem -LiteralPath $pluginDir -Filter *.jar -File | Sort-Object Name | ForEach-Object {
        $meta = Read-PluginMeta $_.FullName
        if ($null -ne $meta -and $meta.id) {
            $plugins += [pscustomobject]@{ Id = [string]$meta.id; Name = [string]$meta.name; Version = [string]$meta.version; Jar = $_.Name }
        }
    }
}

$existingConfig = $null
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try { $existingConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $existingConfig = $null }
}
$existingDisabled = @()
if ($null -ne $existingConfig -and $existingConfig.disabledPlugins) { $existingDisabled = @($existingConfig.disabledPlugins) }

$state = Read-CubismInstallationState -StatePath $statePath
$stateInstallations = @($state.Installations)
$manualRoots = @()
function Refresh-CubismCandidates {
    $savedRoots = @($stateInstallations | ForEach-Object { $_.Root })
    $roots = Get-CubismDiscoveryRoots -SavedRoots $savedRoots -ManualRoots $manualRoots
    $found = Get-CubismInstallations -Roots $roots
    return @(Merge-CubismSelection -Candidates $found -SavedInstallations $stateInstallations)
}
$candidates = Refresh-CubismCandidates

[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = $S.FormTitle -f $turboismHome
$form.Size = New-Object System.Drawing.Size(720, 590)
$form.StartPosition = "CenterScreen"
$form.MinimizeBox = $false
$form.MaximizeBox = $false

$tabs = New-Object System.Windows.Forms.TabControl
$tabs.Location = New-Object System.Drawing.Point(8, 8)
$tabs.Size = New-Object System.Drawing.Size(688, 480)
$pluginPage = New-Object System.Windows.Forms.TabPage
$pluginPage.Text = $S.PluginsTab
$cubismPage = New-Object System.Windows.Forms.TabPage
$cubismPage.Text = $S.CubismTab
[void]$tabs.TabPages.Add($pluginPage)
[void]$tabs.TabPages.Add($cubismPage)
$form.Controls.Add($tabs)

$pluginLabel = New-Object System.Windows.Forms.Label
$pluginLabel.Text = $S.PluginPrompt
$pluginLabel.Location = New-Object System.Drawing.Point(12, 12)
$pluginLabel.AutoSize = $true
$pluginPage.Controls.Add($pluginLabel)
$pluginList = New-Object System.Windows.Forms.CheckedListBox
$pluginList.Location = New-Object System.Drawing.Point(12, 38)
$pluginList.Size = New-Object System.Drawing.Size(650, 360)
$pluginList.CheckOnClick = $true
foreach ($plugin in $plugins) {
    $text = "{0}  [{1}]  v{2}" -f $plugin.Name, $plugin.Id, $plugin.Version
    [void]$pluginList.Items.Add($text, ($existingDisabled -notcontains $plugin.Id))
}
$pluginPage.Controls.Add($pluginList)

$cubismLabel = New-Object System.Windows.Forms.Label
$cubismLabel.Text = $S.CubismPrompt
$cubismLabel.Location = New-Object System.Drawing.Point(12, 12)
$cubismLabel.AutoSize = $true
$cubismPage.Controls.Add($cubismLabel)
$cubismList = New-Object System.Windows.Forms.CheckedListBox
$cubismList.Location = New-Object System.Drawing.Point(12, 38)
$cubismList.Size = New-Object System.Drawing.Size(650, 330)
$cubismList.CheckOnClick = $true
$cubismList.HorizontalScrollbar = $true
$cubismPage.Controls.Add($cubismList)
$cubismStatus = New-Object System.Windows.Forms.Label
$cubismStatus.Location = New-Object System.Drawing.Point(12, 375)
$cubismStatus.AutoSize = $true
$cubismPage.Controls.Add($cubismStatus)

$rescanButton = New-Object System.Windows.Forms.Button
$rescanButton.Text = $S.Rescan
$rescanButton.Location = New-Object System.Drawing.Point(12, 410)
$rescanButton.Size = New-Object System.Drawing.Size(90, 30)
$cubismPage.Controls.Add($rescanButton)
$addButton = New-Object System.Windows.Forms.Button
$addButton.Text = $S.Add
$addButton.Location = New-Object System.Drawing.Point(110, 410)
$addButton.Size = New-Object System.Drawing.Size(110, 30)
$cubismPage.Controls.Add($addButton)
$removeButton = New-Object System.Windows.Forms.Button
$removeButton.Text = $S.Remove
$removeButton.Location = New-Object System.Drawing.Point(228, 410)
$removeButton.Size = New-Object System.Drawing.Size(90, 30)
$cubismPage.Controls.Add($removeButton)

$statusLabel = New-Object System.Windows.Forms.Label
$statusLabel.Location = New-Object System.Drawing.Point(12, 505)
$statusLabel.AutoSize = $true
$form.Controls.Add($statusLabel)
$saveButton = New-Object System.Windows.Forms.Button
$saveButton.Text = $S.Save
$saveButton.Location = New-Object System.Drawing.Point(500, 515)
$saveButton.Size = New-Object System.Drawing.Size(90, 30)
$form.Controls.Add($saveButton)
$cancelButton = New-Object System.Windows.Forms.Button
$cancelButton.Text = $S.Cancel
$cancelButton.Location = New-Object System.Drawing.Point(600, 515)
$cancelButton.Size = New-Object System.Drawing.Size(90, 30)
$cancelButton.Add_Click({ $form.Close() })
$form.Controls.Add($cancelButton)

function Render-CubismCandidates {
    $cubismList.Items.Clear()
    foreach ($candidate in @($candidates)) {
        $stateText = if ($candidate.Selectable) { $S.Ready } elseif ($candidate.Status -eq "Unsupported") { $S.Unsupported } else { $S.Invalid }
        $text = "{0}  |  {1}  |  {2}  |  {3}" -f $(if ($candidate.Version) { $candidate.Version } else { "?" }), $stateText, $candidate.CanonicalRoot, $candidate.Reason
        [void]$cubismList.Items.Add($text, ([bool]$candidate.Selected -and [bool]$candidate.Selectable))
    }
    $selected = @($candidates | Where-Object { $_.Selectable -and $_.Selected }).Count
    $cubismStatus.Text = "$selected $($S.Selected)"
    if ($selected -eq 0 -and @($candidates | Where-Object { $_.Selectable }).Count -eq 0) { $statusLabel.Text = $S.StatusNoCubism }
}
Render-CubismCandidates
if (-not $state.Valid) { $statusLabel.Text = $S.StateWarning -f $state.Error }
if ($plugins.Count -eq 0) { $statusLabel.Text = $S.StatusNoPlugins }

$rescanButton.Add_Click({
    for ($i = 0; $i -lt $candidates.Count; $i++) { $candidates[$i].Selected = $cubismList.GetItemChecked($i) }
    $stateInstallations = @($candidates | ForEach-Object { [pscustomobject]@{ Root = $_.CanonicalRoot; Version = $_.Version; Selected = $_.Selected } })
    $candidates = Refresh-CubismCandidates
    Render-CubismCandidates
})
$addButton.Add_Click({
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = $S.AddTitle
    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $candidate = New-CubismInstallationCandidate -Root $dialog.SelectedPath -Source "manual"
        $manualRoot = $candidate.CanonicalRoot
        if ($null -ne $candidate.Key -and @($candidates | Where-Object { $_.Key -eq $candidate.Key }).Count -eq 0) {
            $manualRoots += $manualRoot
            $candidate.Selected = [bool]$candidate.Selectable
            $candidates += $candidate
            $candidates = @($candidates | Sort-Object @{Expression={ if ($_.Version) { [version]$_.Version } else { [version]"0.0.0" } }}, @{Expression={ $_.CanonicalRoot.ToUpperInvariant() }})
            Render-CubismCandidates
        }
    }
    $dialog.Dispose()
})
$removeButton.Add_Click({
    if ($cubismList.SelectedIndices.Count -eq 0) { $statusLabel.Text = $S.RemovePrompt; return }
    $removeKeys = @($cubismList.SelectedIndices | ForEach-Object { $candidates[$_].Key })
    $remaining = New-Object System.Collections.Generic.List[object]
    foreach ($candidate in @($candidates)) {
        if ($removeKeys -contains $candidate.Key) {
            $isManual = @($manualRoots | Where-Object { (Get-CubismRootKey $_) -eq $candidate.Key }).Count -gt 0
            if (-not $isManual -and $candidate.Selectable) {
                # Auto-discovered usable candidates stay visible but become a saved
                # deselection, so the next scan cannot silently re-enable them.
                $candidate.Selected = $false
            }
            else { continue }
        }
        [void]$remaining.Add($candidate)
    }
    $candidates = @($remaining)
    $stateInstallations = @($stateInstallations | Where-Object { $removeKeys -notcontains (Get-CubismRootKey $_.Root) })
    foreach ($candidate in @($candidates)) {
        $existing = @($stateInstallations | Where-Object { (Get-CubismRootKey $_.Root) -eq $candidate.Key })
        if ($existing.Count -gt 0) { $existing[0].Selected = [bool]$candidate.Selected }
        elseif ($candidate.Selectable) { $stateInstallations += [pscustomobject]@{ Root = $candidate.CanonicalRoot; Version = $candidate.Version; Selected = [bool]$candidate.Selected } }
    }
    $manualRoots = @($manualRoots | Where-Object { $removeKeys -notcontains (Get-CubismRootKey $_) })
    Render-CubismCandidates
})

$saveButton.Add_Click({
    try {
        for ($i = 0; $i -lt $candidates.Count; $i++) {
            $candidates[$i].Selected = [bool]$cubismList.GetItemChecked($i) -and [bool]$candidates[$i].Selectable
        }
        $unchecked = @()
        for ($i = 0; $i -lt $plugins.Count; $i++) { if (-not $pluginList.GetItemChecked($i)) { $unchecked += $plugins[$i].Id } }
        $known = @($plugins | ForEach-Object { $_.Id })
        foreach ($id in $existingDisabled) {
            if (($known -notcontains $id) -and ($unchecked -notcontains $id)) { $unchecked += $id }
        }
        $unchecked = @($unchecked | Sort-Object -Unique)
        $config = [ordered]@{ format = "turboism.runtime.config"; schemaVersion = 1; worktreeId = "turboism-runtime"; pluginDirs = @("plugins") }
        if ($null -ne $existingConfig) {
            foreach ($property in $existingConfig.PSObject.Properties) {
                if (@("format", "schemaVersion", "worktreeId", "pluginDirs", "disabledPlugins") -notcontains $property.Name) {
                    $config[$property.Name] = $property.Value
                }
            }
        }
        if ($unchecked.Count -gt 0) { $config.disabledPlugins = $unchecked }
        else { $config.Remove("disabledPlugins") }
        $json = $config | ConvertTo-Json -Depth 8
        $json = $json -replace '"disabledPlugins":\s*"([^"]+)"', '"disabledPlugins": ["$1"]'
        Set-Content -LiteralPath $configPath -Value $json -Encoding UTF8

        $oldState = Read-CubismInstallationState -StatePath $statePath
        $oldOwned = if ($oldState.Valid) { @($oldState.ManagedShortcuts) } else { @() }
        $newShortcuts = @()
        $newlyCreated = @()
        $stateCommitted = $false
        try {
            foreach ($candidate in @($candidates | Where-Object { $_.Selected -and $_.Selectable })) {
                foreach ($variant in @("normal", "d3d")) {
                    if ($variant -eq "d3d" -and [string]::IsNullOrWhiteSpace($candidate.D3DBat)) { continue }
                    $shortcutPath = Join-Path (Get-CubismShortcutDirectory) (Get-CubismShortcutName $candidate $variant)
                    $wasPresent = Test-Path -LiteralPath $shortcutPath -PathType Leaf
                    if ($wasPresent -and $oldOwned -notcontains $shortcutPath) { throw "refusing to overwrite an unowned managed shortcut: $shortcutPath" }
                    $created = New-CubismManagedShortcut -Home $turboismHome -Candidate $candidate -Variant $variant
                    $newShortcuts += $created
                    if (-not $wasPresent) { $newlyCreated += $created }
                }
            }
            # Keep all previous ownership in the provisional state until stale
            # removal has succeeded. A failed state write rolls back only new files.
            $provisional = @($oldOwned + $newShortcuts | Sort-Object -Unique)
            Write-CubismInstallationState -StatePath $statePath -Candidates $candidates -ManagedShortcuts $provisional
            $stateCommitted = $true
            $stale = @($oldOwned | Where-Object { $newShortcuts -notcontains $_ })
            $failedStale = @(Remove-CubismManagedShortcuts -Paths $stale)
            $finalOwned = @($newShortcuts + $failedStale | Sort-Object -Unique)
            Write-CubismInstallationState -StatePath $statePath -Candidates $candidates -ManagedShortcuts $finalOwned
            $selectedCount = @($candidates | Where-Object { $_.Selected -and $_.Selectable }).Count
            if ($failedStale.Count -gt 0) {
                $statusLabel.Text = "$($S.StatusSaved -f $selectedCount) (stale shortcut cleanup pending)"
            }
            else { $statusLabel.Text = $S.StatusSaved -f $selectedCount }
        }
        catch {
            if (-not $stateCommitted) { [void](Remove-CubismManagedShortcuts -Paths $newlyCreated) }
            throw
        }
        [System.Windows.Forms.MessageBox]::Show($S.Saved, $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
        $form.Close()
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(($S.SaveError -f $_.Exception.Message), $form.Text, [System.Windows.Forms.MessageBoxButtons]::OK, [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
})

[void]$form.ShowDialog()
