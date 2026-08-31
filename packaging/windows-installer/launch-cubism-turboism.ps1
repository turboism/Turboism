# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [string]$HomePath = "",
    [string]$CubismRoot = "",
    [string]$CubismJava = "",
    [string]$ProjectPath = "",
    [ValidateSet("normal", "d3d")]
    [string]$Variant = "normal",
    [switch]$ProbeOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CubismArguments = @()
)

# Managed launcher: state/selection decides the root; the selected root's
# official CubismEditor5.bat remains the only Cubism entry point.
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")
Set-StrictMode -Version 3.0

$uiLang = [System.Threading.Thread]::CurrentThread.CurrentUICulture.TwoLetterISOLanguageName
$messages = @{
    en = @{
        HomeMissing = "Turboism home does not exist: {0}"
        AgentMissing = "Turboism agent is missing: {0}"
        StateMissing = "No managed Cubism installation is selected. Run configure_turboism.ps1 first or pass -CubismRoot."
        StateInvalid = "Managed Cubism state is invalid: {0}. Run configure_turboism.ps1 again."
        RootInvalid = "The selected Cubism root is invalid or unsupported: {0}"
        Multiple = "Multiple Cubism installations are selected. Choose one or pass -CubismRoot."
        Choice = "Choose a Cubism installation (1-{0})"
        ChoiceInvalid = "The selection is not valid."
        D3DMissing = "The selected Cubism installation has no official D3D BAT: {0}"
        ProjectMissing = "Project or file does not exist: {0}"
        Ready = "Managed Cubism launch: {0} ({1})"
        Jvm = "Cubism JVM: {0}"
        GraalFallback = "GraalVM is unavailable. This launch will use Cubism bundled Java instead. Install GraalVM from https://www.graalvm.org/downloads/ and select it again in Turboism Settings."
        Probe = "Managed launch probe passed."
    }
    zh = @{
        HomeMissing = "Turboism home 不存在：{0}"
        AgentMissing = "缺少 Turboism agent：{0}"
        StateMissing = "没有选中的托管 Cubism 安装。请先运行 configure_turboism.ps1，或传入 -CubismRoot。"
        StateInvalid = "托管 Cubism 状态无效：{0}。请重新运行 configure_turboism.ps1。"
        RootInvalid = "所选 Cubism 根目录无效或不支持：{0}"
        Multiple = "选中了多个 Cubism 安装。请选择一个，或传入 -CubismRoot。"
        Choice = "请选择 Cubism 安装（1-{0}）"
        ChoiceInvalid = "选择无效。"
        D3DMissing = "所选 Cubism 安装没有官方 D3D BAT：{0}"
        ProjectMissing = "项目或文件不存在：{0}"
        Ready = "托管 Cubism 启动：{0}（{1}）"
        Jvm = "Cubism JVM：{0}"
        GraalFallback = "未检测到 GraalVM，本次启动将改用 Cubism 内置 Java。请从 https://www.graalvm.org/downloads/ 安装 GraalVM，然后在 Turboism 设置中重新选择。"
        Probe = "托管启动探针通过。"
    }
    ja = @{
        HomeMissing = "Turboism home が存在しません：{0}"
        AgentMissing = "Turboism agent がありません：{0}"
        StateMissing = "管理対象の Cubism インストールが選択されていません。先に configure_turboism.ps1 を実行するか、-CubismRoot を指定してください。"
        StateInvalid = "管理対象 Cubism の状態が不正です：{0}。configure_turboism.ps1 を再実行してください。"
        RootInvalid = "選択した Cubism ルートが不正または未対応です：{0}"
        Multiple = "複数の Cubism インストールが選択されています。1 つ選ぶか、-CubismRoot を指定してください。"
        Choice = "Cubism インストールを選択してください（1-{0}）"
        ChoiceInvalid = "選択が不正です。"
        D3DMissing = "選択した Cubism インストールに公式 D3D BAT がありません：{0}"
        ProjectMissing = "プロジェクトまたはファイルがありません：{0}"
        Ready = "管理対象 Cubism を起動：{0}（{1}）"
        Jvm = "Cubism JVM：{0}"
        GraalFallback = "GraalVM が見つからないため、今回は Cubism 同梱 Java を使用します。https://www.graalvm.org/downloads/ から GraalVM をインストールし、Turboism 設定で再度選択してください。"
        Probe = "管理起動プローブに成功しました。"
    }
}
if (-not $messages.ContainsKey($uiLang)) { $uiLang = "en" }
$M = $messages[$uiLang]

if (-not [string]::IsNullOrWhiteSpace($HomePath)) { $turboismHome = $HomePath.TrimEnd('\', '/') }
elseif (-not [string]::IsNullOrWhiteSpace($env:TURBOISM_HOME)) { $turboismHome = $env:TURBOISM_HOME.TrimEnd('\', '/') }
else { $turboismHome = $scriptDir }
if (-not (Test-Path -LiteralPath $turboismHome -PathType Container)) { throw ($M.HomeMissing -f $turboismHome) }
$agent = Join-Path $turboismHome "turboism-agent.jar"
if (-not (Test-Path -LiteralPath $agent -PathType Leaf)) { throw ($M.AgentMissing -f $agent) }
$statePath = Join-Path $turboismHome "cubism-installations.json"

function Resolve-SelectedCandidate {
    if (-not [string]::IsNullOrWhiteSpace($CubismRoot)) {
        $requested = $CubismRoot.Trim()
        if ([System.IO.Path]::GetExtension($requested) -ieq ".bat") { $requested = Split-Path -Parent $requested }
        $candidate = New-CubismInstallationCandidate -Root $requested -Source "explicit" -TurboismHome $turboismHome
        if (-not $candidate.Selectable) { throw ($M.RootInvalid -f $requested) }
        return $candidate
    }
    $state = Read-CubismInstallationState -StatePath $statePath
    if (-not $state.Valid) { throw ($M.StateInvalid -f $state.Error) }

    $selectedEntries = @($state.Installations | Where-Object { $_.Selected })
    if ($selectedEntries.Count -eq 0) { throw $M.StateMissing }
    $selected = @()
    foreach ($entry in $selectedEntries) {
        $candidate = New-CubismInstallationCandidate -Root $entry.Root -Source "managed" -TurboismHome $turboismHome
        if (-not $candidate.Selectable) {
            throw ($M.RootInvalid -f "$($entry.Root): $($candidate.Reason)")
        }
        $selected += $candidate
    }
    if ($selected.Count -eq 1) { return $selected[0] }

    Write-Host $M.Multiple
    for ($index = 0; $index -lt $selected.Count; $index++) {
        Write-Host (("  {0}. {1}  {2}" -f ($index + 1), $selected[$index].Version, $selected[$index].CanonicalRoot))
    }
    $answer = Read-Host ($M.Choice -f $selected.Count)
    $number = 0
    if (-not [int]::TryParse($answer, [ref]$number) -or $number -lt 1 -or $number -gt $selected.Count) {
        throw $M.ChoiceInvalid
    }
    return $selected[$number - 1]
}

$cubism = Resolve-SelectedCandidate
if ($Variant -eq "d3d") {
    if ([string]::IsNullOrWhiteSpace($cubism.D3DBat)) { throw ($M.D3DMissing -f $cubism.CanonicalRoot) }
    $officialBat = $cubism.D3DBat
}
else { $officialBat = $cubism.OfficialBat }

$batArguments = @()
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) {
    if (-not (Test-Path -LiteralPath $ProjectPath)) { throw ($M.ProjectMissing -f $ProjectPath) }
    $batArguments += (Resolve-Path -LiteralPath $ProjectPath).Path
}
$batArguments += @($CubismArguments)
Write-Host ($M.Ready -f $cubism.Version, $cubism.CanonicalRoot)

$cubismJvm = Read-CubismJvmPreference -TurboismHome $turboismHome
$javaOverride = ""
if (-not [string]::IsNullOrWhiteSpace($CubismJava)) {
    $javaOverride = Resolve-CubismGraalJava -TurboismHome $turboismHome -ExplicitJava $CubismJava
    $cubismJvm = "graalvm"
}
elseif ($cubismJvm -eq "bundled") {
    # The persisted recovery choice is authoritative. Environment discovery
    # must not silently turn bundled mode back into GraalVM.
    $javaOverride = ""
}
else {
    $javaOverride = Find-CubismGraalJava -TurboismHome $turboismHome
    if ([string]::IsNullOrWhiteSpace($javaOverride)) {
        $cubismJvm = "bundled"
        Write-Warning $M.GraalFallback
    }
}
Write-Host ($M.Jvm -f $(if ($cubismJvm -eq "graalvm") { $javaOverride } else { $cubism.Java }))
$graalHost = Resolve-TurboismGraalHost `
    -TurboismHome $turboismHome `
    -PreferredJava $(if ($cubismJvm -eq "graalvm") { $javaOverride } else { "" })

if ($ProbeOnly) {
    Write-Host $M.Probe
    exit 0
}

$launchBat = $officialBat
$temporaryBat = ""
try {
    if ($cubismJvm -eq "graalvm") {
        $temporaryBat = New-CubismJavaOverrideBat `
            -OfficialBat $officialBat `
            -CubismRoot $cubism.CanonicalRoot `
            -TurboismHome $turboismHome `
            -JavaExecutable $javaOverride
        $launchBat = $temporaryBat
    }
    $exitCode = Invoke-CubismOfficialBat `
        -OfficialBat $launchBat `
        -CubismRoot $cubism.CanonicalRoot `
        -TurboismHome $turboismHome `
        -Agent $agent `
        -GraalHost $graalHost `
        -Arguments $batArguments
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($temporaryBat) -and (Test-Path -LiteralPath $temporaryBat -PathType Leaf)) {
        Remove-Item -LiteralPath $temporaryBat -Force -ErrorAction SilentlyContinue
    }
}
exit $exitCode
