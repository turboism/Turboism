# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [string]$Home = "",
    [switch]$WriteBat
)

# Turboism 插件开关配置工具（WinForms）。
# 列出 <home>/plugins/*.jar 的插件（读取 jar 内 META-INF/turboism/plugin.json），
# 勾选 = 启用；保存时把未勾选插件 id 写入 <home>/config.json 的 disabledPlugins
# （升序；既有 disabledPlugins 中已不存在的插件 id 一并保留）。
# 注意：本文件为 UTF-8 with BOM，可在 Windows PowerShell 5.1 直接运行。

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ErrorActionPreference = "Stop"

# ---------- home 解析：-Home > TURBOISM_HOME > 脚本所在目录 ----------
if (-not [string]::IsNullOrWhiteSpace($Home)) {
    $turboismHome = $Home.TrimEnd('\', '/')
}
elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) {
    $turboismHome = $env:TURBOISM_HOME.TrimEnd('\', '/')
}
else {
    $turboismHome = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if (-not (Test-Path -LiteralPath $turboismHome -PathType Container)) {
    throw "Turboism home does not exist: $turboismHome"
}
$configPath = Join-Path $turboismHome "config.json"
$pluginDir = Join-Path $turboismHome "plugins"

# ---------- 插件清单 ----------
function Read-PluginMeta {
    param([string]$JarPath)
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            $entry = $zip.GetEntry("META-INF/turboism/plugin.json")
            if ($null -eq $entry) { return $null }
            $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            return ($text | ConvertFrom-Json)
        }
        finally { $zip.Dispose() }
    }
    catch { return $null }
}

$plugins = @()
if (Test-Path -LiteralPath $pluginDir -PathType Container) {
    Get-ChildItem -LiteralPath $pluginDir -Filter *.jar -File | Sort-Object Name | ForEach-Object {
        $meta = Read-PluginMeta -JarPath $_.FullName
        if ($null -ne $meta -and $meta.id) {
            $plugins += [pscustomobject]@{
                Id      = $meta.id
                Name    = $meta.name
                Version = $meta.version
                Jar     = $_.Name
            }
        }
    }
}

# ---------- 既有配置 ----------
$existingDisabled = @()
$existingConfig = $null
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    try { $existingConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json } catch { $existingConfig = $null }
}
if ($null -ne $existingConfig -and $existingConfig.disabledPlugins) {
    $existingDisabled = @($existingConfig.disabledPlugins)
}

# ---------- WinForms 界面 ----------
[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = "Turboism 插件配置 - $turboismHome"
$form.Size = New-Object System.Drawing.Size(600, 500)
$form.StartPosition = "CenterScreen"
$form.MinimizeBox = $false
$form.MaximizeBox = $false

$label = New-Object System.Windows.Forms.Label
$label.Text = "勾选需要启用的插件（未勾选的插件 id 将写入 config.json 的 disabledPlugins）："
$label.Location = New-Object System.Drawing.Point(12, 10)
$label.AutoSize = $true
$form.Controls.Add($label)

$list = New-Object System.Windows.Forms.CheckedListBox
$list.Location = New-Object System.Drawing.Point(12, 34)
$list.Size = New-Object System.Drawing.Size(560, 350)
$list.CheckOnClick = $true
$list.HorizontalScrollbar = $true
foreach ($p in $plugins) {
    $checked = ($existingDisabled -notcontains $p.Id)
    $itemText = "{0}  [{1}]  v{2}" -f $p.Name, $p.Id, $p.Version
    [void]$list.Items.Add($itemText, $checked)
}
$form.Controls.Add($list)

$writeBatBox = New-Object System.Windows.Forms.CheckBox
$writeBatBox.Text = "同时写入 Cubism 启动脚本（bat）"
$writeBatBox.Location = New-Object System.Drawing.Point(12, 396)
$writeBatBox.AutoSize = $true
$writeBatBox.Enabled = $false
$writeBatBox.ToolTipText = "Cubism bat 注入尚未实现（TODO）"
$form.Controls.Add($writeBatBox)

$statusLabel = New-Object System.Windows.Forms.Label
$statusLabel.Location = New-Object System.Drawing.Point(12, 425)
$statusLabel.AutoSize = $true
$form.Controls.Add($statusLabel)

$saveButton = New-Object System.Windows.Forms.Button
$saveButton.Text = "保存"
$saveButton.Location = New-Object System.Drawing.Point(12, 390)
$saveButton.Size = New-Object System.Drawing.Size(90, 30)
$form.Controls.Add($saveButton)

$cancelButton = New-Object System.Windows.Forms.Button
$cancelButton.Text = "取消"
$cancelButton.Location = New-Object System.Drawing.Point(110, 390)
$cancelButton.Size = New-Object System.Drawing.Size(90, 30)
$cancelButton.Add_Click({ $form.Close() })
$form.Controls.Add($cancelButton)

if ($plugins.Count -eq 0) {
    $statusLabel.Text = "未在 plugins/ 下找到有效插件 jar。"
}

$saveButton.Add_Click({
    $unchecked = @()
    for ($i = 0; $i -lt $plugins.Count; $i++) {
        if (-not $list.GetItemChecked($i)) { $unchecked += $plugins[$i].Id }
    }
    # 保留既有 disabledPlugins 中已不在插件列表中的 id
    $known = @($plugins | ForEach-Object { $_.Id })
    foreach ($id in $existingDisabled) {
        if (($known -notcontains $id) -and ($unchecked -notcontains $id)) { $unchecked += $id }
    }
    $unchecked = @($unchecked | Sort-Object -Unique)

    if ($null -eq $existingConfig) {
        $config = [pscustomobject]@{
            format       = "turboism.runtime.config"
            schemaVersion = 1
            worktreeId   = "turboism-runtime"
            pluginDirs   = @("plugins")
        }
    }
    else {
        $config = $existingConfig
    }
    if ($unchecked.Count -gt 0) {
        $config.disabledPlugins = $unchecked
    }
    else {
        $config.PSObject.Properties.Remove("disabledPlugins")
    }

    $json = $config | ConvertTo-Json -Depth 8
    # Windows PowerShell 5.1 的 ConvertTo-Json 会把单元素数组展开成标量，这里修复
    $json = $json -replace '"disabledPlugins":\s*"([^"]+)"', '"disabledPlugins": ["$1"]'
    Set-Content -LiteralPath $configPath -Value $json -Encoding UTF8
    $statusLabel.Text = "已保存 config.json（禁用 $($unchecked.Count) 个插件）。"
})

# ---------- Cubism bat 写入（接口占位） ----------
function Write-CubismBat {
    # TODO: 未实现 —— 向 Cubism 的 CubismEditor5.bat 注入 Turboism 启动参数
    # （侵入式修改 Cubism 安装目录，需用户明确确认；后续版本实现）。
    throw "Write-CubismBat 尚未实现（TODO）：Cubism bat 注入逻辑未完成。"
}
if ($WriteBat) {
    Write-CubismBat
}

[void]$form.ShowDialog()
