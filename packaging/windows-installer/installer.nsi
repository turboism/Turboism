; -*- coding: utf-8 -*-
; Turboism Windows 安装器 — NSIS MUI2（Unicode）
;
; 构建（由 assemble-release.sh 调用，也可手动）：
;   makensis -WX -DVER=<ver> -DSTAGING_DIR=<abs> -DOUT_DIR=<abs> installer.nsi
;
; 依赖：
;   - plugin-sections.nsh 由 assemble-release.sh 从插件 jar 的
;     META-INF/turboism/plugin.json 生成（每个插件一个 Section）。
;   - ${STAGING_DIR} 为 staging 目录（turboism-agent.jar、launch 脚本、
;     README.txt / README.zh.txt / README.ja.txt、plugins/*.jar）；LICENSE 由 ${LICENSE_FILE} 指定。
;
; 插件 Section 通过 ${SEC_<id>} 编译期常量（Section 索引）访问，与声明顺序无关；
; 生成文件内含隐藏载荷 Section（$Mode==1 时安装全部插件 JAR）与
; SetPluginSectionsSelected / CollectUncheckedPluginIds / RemoveBundledFromExistingDisabled 三个函数。

Unicode true

!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "Sections.nsh"

; ---------- 编译期参数（assemble-release.sh 传入绝对路径；缺省值供独立编译） ----------
; 注意：makensis 将脚本内相对路径解析为相对于脚本所在目录，
; 因此缺省值用从 packaging/windows-installer/ 出发的相对路径。
!ifndef VER
  !define VER "0.0.0-dev"
!endif
!ifndef STAGING_DIR
  !define STAGING_DIR "../../build/windows-installer/staging"
!endif
!ifndef OUT_DIR
  !define OUT_DIR "../../build/windows-installer/dist"
!endif
!ifndef LICENSE_FILE
  !define LICENSE_FILE "../../LICENSE"
!endif

; ---------- 基本属性 ----------
Name "Turboism"
OutFile "${OUT_DIR}/TurboismInstaller-${VER}.exe"
InstallDir "$LOCALAPPDATA\Turboism"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!ifdef VER_NUMERIC
  VIProductVersion "${VER_NUMERIC}"
  VIAddVersionKey "ProductName" "Turboism"
  VIAddVersionKey "ProductVersion" "${VER}"
  VIAddVersionKey "FileDescription" "Turboism Windows Installer"
  VIAddVersionKey "FileVersion" "${VER}"
  VIAddVersionKey "CompanyName" "Turboism Contributors"
  VIAddVersionKey "LegalCopyright" "Copyright (c) Turboism Contributors"
!endif

; ---------- MUI 设置（三语言文案经 LangString 定义，见语言区块；Unicode true + UTF-8 BOM 源文件） ----------
; MUI_ABORTWARNING 的提示文本由 MUI 语言文件按语言提供（已本地化）
!define MUI_ABORTWARNING
!define MUI_COMPONENTSPAGE_SMALLDESC

!define MUI_WELCOMEPAGE_TITLE "$(TurboismWelcomeTitle)"
!define MUI_WELCOMEPAGE_TEXT "$(TurboismWelcomeText)"

!define MUI_LICENSEPAGE_TEXT_TOP "$(LicenseTopText)"

!define MUI_DIRECTORYPAGE_TEXT_TOP "$(DirectoryTopText)"

!define MUI_FINISHPAGE_TITLE "$(FinishTitleText)"
!define MUI_FINISHPAGE_TEXT "$(FinishBodyText)"

; ---------- 页面流程：Welcome → License → 模式选择 → Components(仅 Full) → Directory → InstFiles → Finish ----------
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"
Page custom ModeCreate ModeLeave
!define MUI_PAGE_CUSTOMFUNCTION_PRE ComponentsPre
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

; ---------- 卸载页：Confirm（含 config.json 复选框）→ InstFiles → Finish（必须在 MUI_LANGUAGE 之前插入） ----------
!define MUI_UNCONFIRMPAGE_TEXT_TOP "$(UnConfirmTextTop)"
!define MUI_UNCONFIRMPAGE_TEXT_LOCATION "$INSTDIR"
!define MUI_PAGE_CUSTOMFUNCTION_SHOW un.ConfirmShow
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE un.ConfirmLeave
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

; ---------- 语言：三语言共存，运行时按系统语言自动选择（$LANGUAGE），首个为缺省 ----------
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "Japanese"

; ---------- 自定义文案 LangString（en/zh/ja） ----------
LangString TurboismWelcomeTitle ${LANG_ENGLISH} "Welcome to Turboism Setup"
LangString TurboismWelcomeTitle ${LANG_SIMPCHINESE} "欢迎安装 Turboism"
LangString TurboismWelcomeTitle ${LANG_JAPANESE} "Turboism セットアップへようこそ"

LangString TurboismWelcomeText ${LANG_ENGLISH} "This wizard will install Turboism, an enhanced runtime for the Live2D Cubism editor.$\r$\n$\r$\nInstallation is per-user and does not modify the Cubism installation directory.$\r$\n$\r$\nAfter installation, start the Cubism editor with launch-cubism-turboism.bat in the installation directory.$\r$\n$\r$\nClick Next to continue."
LangString TurboismWelcomeText ${LANG_SIMPCHINESE} "本向导将安装 Turboism —— Live2D Cubism 编辑器的增强运行时。$\r$\n$\r$\n安装为免管理员模式，不会修改 Cubism 安装目录。$\r$\n$\r$\n安装完成后，请通过安装目录中的 launch-cubism-turboism.bat 启动 Cubism 编辑器。$\r$\n$\r$\n点击“下一步”继续。"
LangString TurboismWelcomeText ${LANG_JAPANESE} "このウィザードは Live2D Cubism エディター用の拡張ランタイム「Turboism」をインストールします。$\r$\n$\r$\nインストールは管理者権限不要で、Cubism のインストール先ディレクトリは変更しません。$\r$\n$\r$\nインストール後、インストール先ディレクトリの launch-cubism-turboism.bat から Cubism エディターを起動してください。$\r$\n$\r$\n「次へ」をクリックして続行します。"

LangString LicenseTopText ${LANG_ENGLISH} "Please review the license terms before installing Turboism. Scroll down to see the full text:"
LangString LicenseTopText ${LANG_SIMPCHINESE} "请阅读以下许可协议。滚动查看全文："
LangString LicenseTopText ${LANG_JAPANESE} "インストール前に以下の使用許諾契約をお読みください。全文を表示するには下へスクロールしてください："

LangString DirectoryTopText ${LANG_ENGLISH} "Turboism will be installed to the following directory (Turboism home):"
LangString DirectoryTopText ${LANG_SIMPCHINESE} "Turboism 将安装到以下目录（Turboism home）："
LangString DirectoryTopText ${LANG_JAPANESE} "Turboism は次のディレクトリ（Turboism home）にインストールされます："

LangString FinishTitleText ${LANG_ENGLISH} "Installation Complete"
LangString FinishTitleText ${LANG_SIMPCHINESE} "安装完成"
LangString FinishTitleText ${LANG_JAPANESE} "インストール完了"

LangString FinishBodyText ${LANG_ENGLISH} "Turboism has been installed to:$\r$\n$INSTDIR$\r$\n$\r$\nTo launch: double-click launch-cubism-turboism.bat (auto-detects the Cubism installation directory).$\r$\nTo adjust plugins: run configure_turboism.ps1.$\r$\n$\r$\nSee README.txt in the installation directory for details."
LangString FinishBodyText ${LANG_SIMPCHINESE} "Turboism 已安装到：$\r$\n$INSTDIR$\r$\n$\r$\n启动方式：双击 launch-cubism-turboism.bat（自动探测 Cubism 安装目录）。$\r$\n调整插件开关：运行 configure_turboism.ps1。$\r$\n$\r$\n详细说明见安装目录中的 README.txt。"
LangString FinishBodyText ${LANG_JAPANESE} "Turboism は次の場所にインストールされました：$\r$\n$INSTDIR$\r$\n$\r$\n起動方法：launch-cubism-turboism.bat をダブルクリックします（Cubism のインストール先を自動検出します）。$\r$\nプラグインの切り替え：configure_turboism.ps1 を実行します。$\r$\n$\r$\n詳細はインストール先の README.txt を参照してください。"

LangString UnConfirmTextTop ${LANG_ENGLISH} "Turboism will be uninstalled from the following folder:$\r$\nClick Yes to remove it:"
LangString UnConfirmTextTop ${LANG_SIMPCHINESE} "Turboism 将从以下文件夹卸载：$\r$\n点击“是”开始卸载。"
LangString UnConfirmTextTop ${LANG_JAPANESE} "Turboism は次のフォルダーからアンインストールされます：$\r$\n「はい」をクリックすると削除します："

LangString UnDeleteConfigLabel ${LANG_ENGLISH} "Also delete config.json (user configuration)"
LangString UnDeleteConfigLabel ${LANG_SIMPCHINESE} "同时删除 config.json（用户配置）"
LangString UnDeleteConfigLabel ${LANG_JAPANESE} "config.json（ユーザー設定）も削除する"

LangString ModePageTitle ${LANG_ENGLISH} "Select the installation mode:"
LangString ModePageTitle ${LANG_SIMPCHINESE} "请选择安装模式："
LangString ModePageTitle ${LANG_JAPANESE} "インストールモードを選択してください："

LangString ModeFullLabel ${LANG_ENGLISH} "Full installation (Full) — installs all plugins; you can disable some plugins on the next page (default)"
LangString ModeFullLabel ${LANG_SIMPCHINESE} "完整安装（Full）—— 安装全部插件，可在下一步选择禁用部分插件（默认）"
LangString ModeFullLabel ${LANG_JAPANESE} "フルインストール（Full）—— すべてのプラグインをインストールします。次のページで一部のプラグインを無効化できます（既定）"

LangString ModeLiteLabel ${LANG_ENGLISH} "Lite installation (Lite) — installs only the core runtime, no plugins"
LangString ModeLiteLabel ${LANG_SIMPCHINESE} "精简安装（Lite）—— 仅安装核心运行时，不安装任何插件"
LangString ModeLiteLabel ${LANG_JAPANESE} "ライトインストール（Lite）—— コアランタイムのみインストールし、プラグインはインストールしません"


LangString StartMenuConfigName ${LANG_ENGLISH} "Turboism Configurator"
LangString StartMenuConfigName ${LANG_SIMPCHINESE} "Turboism 配置器"
LangString StartMenuConfigName ${LANG_JAPANESE} "Turboism 設定"

LangString StartMenuUninstallName ${LANG_ENGLISH} "Uninstall Turboism"
LangString StartMenuUninstallName ${LANG_SIMPCHINESE} "卸载 Turboism"
LangString StartMenuUninstallName ${LANG_JAPANESE} "Turboism をアンインストール"

LangString StartMenuLaunchName ${LANG_ENGLISH} "Launch Cubism"
LangString StartMenuLaunchName ${LANG_SIMPCHINESE} "启动 Cubism"
LangString StartMenuLaunchName ${LANG_JAPANESE} "Cubism を起動"

LangString ConfigWriteError ${LANG_ENGLISH} "Cannot write config.json: $INSTDIR\config.json"
LangString ConfigWriteError ${LANG_SIMPCHINESE} "无法写入 config.json：$INSTDIR\config.json"
LangString ConfigWriteError ${LANG_JAPANESE} "config.json を書き込めません：$INSTDIR\config.json"
LangString ShortcutCleanupFailure ${LANG_ENGLISH} "Turboism shortcut restoration/cleanup failed. Nothing else was removed; retry uninstall after resolving the conflict."
LangString ShortcutCleanupFailure ${LANG_SIMPCHINESE} "Turboism 快捷方式恢复/清理失败。未删除其他内容；解决冲突后请重试卸载。"
LangString ShortcutCleanupFailure ${LANG_JAPANESE} "Turboism のショートカットの復元/クリーンアップに失敗しました。他の項目は削除していません。競合を解決してからアンインストールを再試行してください。"

; ---------- 变量 ----------
Var Mode                 ; 0 = Lite, 1 = Full（默认 Full）
Var ModeDialog
Var LiteRadio
Var FullRadio
Var uncheckedPluginIds   ; 本次未勾选的插件 id，';' 分隔（Full 模式）
Var existingDisabled     ; 既有 config.json 的 disabledPlugins，';' 分隔
Var disabledFinal        ; 合并排序后的列表，';' 分隔
Var configBuffer         ; 既有 config.json 全文（≤64KB）
Var configHandle
Var json                 ; 待写入的 config.json 内容
Var sorted
Var head
Var walk
Var id
Var cand
Var needle
Var needleLen
Var cfgLen
Var found
Var pos
Var chunk
Var ch
Var len
Var line
Var next
Var unDeleteConfig      ; 卸载时是否删除 config.json（1 = 删除，默认勾选）
Var unCfgCheckbox       ; 卸载确认页复选框句柄
Var unCfgStyle          ; 复选框控件样式

; 控件样式常量（WinMessages.nsh 未覆盖；已定义时跳过）
!ifndef WS_CHILD
  !define WS_CHILD 0x40000000
!endif
!ifndef WS_VISIBLE
  !define WS_VISIBLE 0x10000000
!endif
!ifndef WS_TABSTOP
  !define WS_TABSTOP 0x00010000
!endif

; ---------- 初始化 ----------
Function .onInit
  StrCpy $Mode 1
  StrCpy $INSTDIR "$LOCALAPPDATA\Turboism"
FunctionEnd

; 安装完成后自动进入托管 Cubism 选择配置；取消不会阻止框架安装。
Function .onInstSuccess
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR"'
FunctionEnd

; ---------- 模式选择页（nsDialogs） ----------
Function ModeCreate
  nsDialogs::Create 1018
  Pop $ModeDialog
  ${If} $ModeDialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "$(ModePageTitle)"
  Pop $0
  ${NSD_CreateRadioButton} 0 34u 100% 14u "$(ModeFullLabel)"
  Pop $FullRadio
  ${NSD_CreateRadioButton} 0 52u 100% 14u "$(ModeLiteLabel)"
  Pop $LiteRadio
  ${If} $Mode == 1
    ${NSD_Check} $FullRadio
  ${Else}
    ${NSD_Check} $LiteRadio
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function ModeLeave
  ${NSD_GetState} $FullRadio $0
  ${If} $0 == 1
    StrCpy $Mode 1
  ${Else}
    StrCpy $Mode 0
  ${EndIf}
  ; Lite：取消全部插件 Section（其代码将不执行）；Full：恢复全选
  StrCpy $0 $Mode
  Call SetPluginSectionsSelected
FunctionEnd

Function ComponentsPre
  ${If} $Mode == 0
    Abort        ; Lite 模式跳过组件选择页
  ${EndIf}
FunctionEnd

; ---------- Section 声明 ----------
; 插件 Section 由 plugin-sections.nsh 提供（见文件头注释）
Section "-核心文件" SecCore
  SetOutPath "$INSTDIR"
  SetOverwrite on
  File "${STAGING_DIR}/turboism-agent.jar"
  File "${STAGING_DIR}/launch-cubism-turboism.bat"
  File "${STAGING_DIR}/launch-cubism-turboism.ps1"
  File "${STAGING_DIR}/configure_turboism.ps1"
  File "${STAGING_DIR}/cubism-launch-common.ps1"
  File "${STAGING_DIR}/README.txt"
  File "${STAGING_DIR}/README.zh.txt"
  File "${STAGING_DIR}/README.ja.txt"
  File "${LICENSE_FILE}"
SectionEnd

; 插件 Section + 描述 + 选择状态函数（由 assemble-release.sh 生成，勿手改）
!include "plugin-sections.nsh"

Section "-写入配置" SecConfig
  ; 收集本次未勾选的插件 id（两种模式都收集：Lite 下 ModeLeave 已取消全部
  ; 插件 Section，因此收集到全部捆绑 id —— 防止 Full→Lite 后陈旧 JAR 加载）
  StrCpy $uncheckedPluginIds ""
  Call CollectUncheckedPluginIds
  ; 读取既有 config.json 的 disabledPlugins（合并时保留）
  StrCpy $existingDisabled ""
  Call ReadExistingDisabledPlugins
  ; 合并 + 排序 + 写回（从模板重建；worktreeId/pluginDirs 固定覆盖）
  Call MergeAndWriteConfig
SectionEnd

; ---------- 配置合并 ----------
; 语义（与 SPEC.md 一致）：先从既有 disabledPlugins 移除全部当前捆绑插件 id
; （重选已捆绑插件即启用），再合并本次未勾选插件（Lite 下为全部捆绑 id）；
; 无关 id 保留；worktreeId 覆盖为 turboism-runtime，pluginDirs 覆盖为 ["plugins"]。
; 其它字段（logLevel/hooks 等）不保留，由运行时默认值补全；
; 需要完整保留既有配置的字段时请使用 configure_turboism.ps1。
; 注意：config.json 内容为纯 ASCII，FileWrite（Unicode 安装器下按 ACP 转换）安全。

; 输入: $0 = ';' 分隔列表；输出: $0 = 首段, $1 = 剩余（无 ';' 时 $0 保持整表、$1 为空）
; 仅使用 scratch 寄存器 $pos/$len/$ch/$next，不改写其它共享寄存器。
Function SplitFirst
  StrCpy $1 ""
  StrCpy $pos 0
  StrLen $len $0
  ${Do}
    ${If} $pos >= $len
      ${ExitDo}
    ${EndIf}
    StrCpy $ch "$0" 1 $pos
    ${If} $ch == ";"
      IntOp $next $pos + 1
      StrCpy $1 "$0" "" $next
      StrCpy $0 "$0" $pos
      ${ExitDo}
    ${EndIf}
    IntOp $pos $pos + 1
  ${Loop}
FunctionEnd

; 通用列表项删除：$0 = ';' 分隔列表，$1 = 要删除的项（删除全部精确匹配）
; 输出：$0 = 删除后的列表（其余项保持原序）；$1 = 要删除的项（原样返回）。
; 调用方通过 SplitFirst 约定共享 $0/$1，本函数仅使用 $3/$5。
Function RemoveItemFromList
  StrCpy $5 "$1"          ; 备份待删除项（SplitFirst 会改写 $0/$1）
  StrCpy $3 ""            ; 结果
  ${Do}
    ${If} $0 == ""
      ${ExitDo}
    ${EndIf}
    Call SplitFirst         ; $0 = 首段, $1 = 剩余
    ${If} $0 != $5
      ${If} $3 == ""
        StrCpy $3 "$0"
      ${Else}
        StrCpy $3 "$3;$0"
      ${EndIf}
    ${EndIf}
    StrCpy $0 "$1"          ; 继续处理剩余列表
  ${Loop}
  StrCpy $0 "$3"
  StrCpy $1 "$5"
FunctionEnd

; 读取 $INSTDIR\config.json 的 disabledPlugins 到 $existingDisabled（';' 分隔）
Function ReadExistingDisabledPlugins
  ${IfNot} ${FileExists} "$INSTDIR\config.json"
    Return
  ${EndIf}
  FileOpen $configHandle "$INSTDIR\config.json" r
  ${If} $configHandle == ""
    Return
  ${EndIf}
  StrCpy $configBuffer ""
  ${Do}
    FileRead $configHandle $line
    ${If} ${Errors}
      ${ExitDo}
    ${EndIf}
    StrCpy $configBuffer "$configBuffer$line"
    StrLen $len $configBuffer
    ${If} $len > 65536
      ${ExitDo}
    ${EndIf}
  ${Loop}
  FileClose $configHandle
  ; 查找 "disabledPlugins"
  StrCpy $needle '"disabledPlugins"'
  StrLen $needleLen $needle
  StrLen $cfgLen $configBuffer
  StrCpy $pos 0
  StrCpy $found -1
  ${Do}
    ${If} $pos >= $cfgLen
      ${ExitDo}
    ${EndIf}
    StrCpy $chunk "$configBuffer" $needleLen $pos
    ${If} $chunk == $needle
      StrCpy $found $pos
      ${ExitDo}
    ${EndIf}
    IntOp $pos $pos + 1
  ${Loop}
  ${If} $found == -1
    Return
  ${EndIf}
  ; 定位数组 '['（跳过键名与冒号、空白）
  IntOp $pos $found + $needleLen
  ${Do}
    ${If} $pos >= $cfgLen
      Return
    ${EndIf}
    StrCpy $ch "$configBuffer" 1 $pos
    ${If} $ch == "["
      ${ExitDo}
    ${EndIf}
    IntOp $pos $pos + 1
  ${Loop}
  ; 解析引号字符串直到 ']'（处理 \" 与 \\ 转义）
  IntOp $pos $pos + 1
  ${Do}
    ${If} $pos >= $cfgLen
      ${ExitDo}
    ${EndIf}
    StrCpy $ch "$configBuffer" 1 $pos
    ${If} $ch == "]"
      ${ExitDo}
    ${EndIf}
    ${If} $ch == '"'
      IntOp $pos $pos + 1
      StrCpy $id ""
      ${Do}
        ${If} $pos >= $cfgLen
          ${ExitDo}
        ${EndIf}
        StrCpy $ch "$configBuffer" 1 $pos
        ${If} $ch == '\'
          IntOp $pos $pos + 2
          ${Continue}
        ${EndIf}
        ${If} $ch == '"'
          IntOp $pos $pos + 1
          ${ExitDo}
        ${EndIf}
        StrCpy $id "$id$ch"
        IntOp $pos $pos + 1
      ${Loop}
      ${If} $id != ""
        ${If} $existingDisabled == ""
          StrCpy $existingDisabled "$id"
        ${Else}
          StrCpy $existingDisabled "$existingDisabled;$id"
        ${EndIf}
      ${EndIf}
    ${Else}
      IntOp $pos $pos + 1
    ${EndIf}
  ${Loop}
FunctionEnd

; $uncheckedPluginIds + $existingDisabled → $disabledFinal（合并、去重、升序）
; 先由生成函数 RemoveBundledFromExistingDisabled 逐 id 移除 $existingDisabled 中的
; 当前捆绑 id（通用 RemoveItemFromList 辅助，无长度受限的合并 id 字符串），
; 再合并本次未勾选插件。
Function MergeAndWriteConfig
  Call RemoveBundledFromExistingDisabled
  StrCpy $disabledFinal "$uncheckedPluginIds"
  ${If} $existingDisabled != ""
    ${If} $disabledFinal != ""
      StrCpy $disabledFinal "$disabledFinal;$existingDisabled"
    ${Else}
      StrCpy $disabledFinal "$existingDisabled"
    ${EndIf}
  ${EndIf}
  ; 插入排序 + 去重
  StrCpy $sorted ""
  ${Do}
    ${If} $disabledFinal == ""
      ${ExitDo}
    ${EndIf}
    StrCpy $0 "$disabledFinal"
    Call SplitFirst
    StrCpy $id $0
    StrCpy $disabledFinal $1
    StrCpy $head ""
    StrCpy $walk "$sorted"
    ${Do}
      ${If} $walk == ""
        ${If} $head == ""
          StrCpy $head "$id"
        ${Else}
          StrCpy $head "$head;$id"
        ${EndIf}
        ${ExitDo}
      ${EndIf}
      StrCpy $0 "$walk"
      Call SplitFirst
      StrCpy $cand $0
      StrCpy $walk $1
      ${If} $cand == $id
        ; 重复：保留既有项，跳过 $id
        ${If} $head == ""
          StrCpy $head "$cand"
        ${Else}
          StrCpy $head "$head;$cand"
        ${EndIf}
        ${If} $walk != ""
          StrCpy $head "$head;$walk"
        ${EndIf}
        ${ExitDo}
      ${EndIf}
      ${If} $cand S> $id          ; 插件 id 均为小写，lstrcmpi 排序与字节序一致
        Goto InsertBefore
      ${EndIf}
      ; $cand < $id：保留 $cand，继续
      ${If} $head == ""
        StrCpy $head "$cand"
      ${Else}
        StrCpy $head "$head;$cand"
      ${EndIf}
      ${Continue}
    InsertBefore:
      ; $cand > $id：在 $cand 前插入 $id
      ${If} $head == ""
        StrCpy $head "$id"
      ${Else}
        StrCpy $head "$head;$id"
      ${EndIf}
      StrCpy $head "$head;$cand"
      ${If} $walk != ""
        StrCpy $head "$head;$walk"
      ${EndIf}
      ${ExitDo}
    ${Loop}
    StrCpy $sorted "$head"
  ${Loop}
  StrCpy $disabledFinal "$sorted"
  ; 组 JSON（模板 + 可选 disabledPlugins；空列表不写出该字段）
  StrCpy $json '{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"turboism-runtime","pluginDirs":["plugins"]'
  ${If} $disabledFinal != ""
    StrCpy $json '$json,"disabledPlugins":['
    StrCpy $head ""
    StrCpy $walk "$disabledFinal"
    ${Do}
      ${If} $walk == ""
        ${ExitDo}
      ${EndIf}
      StrCpy $0 "$walk"
      Call SplitFirst
      StrCpy $walk $1
      ${If} $head != ""
        StrCpy $head '$head","'
      ${EndIf}
      StrCpy $head '$head"$0"'
    ${Loop}
    StrCpy $json "$json$head"
    StrCpy $json '$json]'
  ${EndIf}
  StrCpy $json '$json}$\r$\n'
  ; 写入
  FileOpen $configHandle "$INSTDIR\config.json" w
  ${If} $configHandle == ""
    MessageBox MB_ICONSTOP "$(ConfigWriteError)"
    Abort
  ${EndIf}
  FileWrite $configHandle $json
  FileClose $configHandle
FunctionEnd

; ---------- 卸载器 ----------
; 独立隐藏 Section：安装时写入 uninstall.exe
Section -"卸载器" SecUninstaller
  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

; ---------- 开始菜单快捷方式 + HKCU 卸载注册项 ----------
; 隐藏 Section：必须位于 WriteUninstaller 之后（CreateShortCut 在目标文件不存在时报错），
; 且快捷方式「起始位置」(Start In) 取创建时的 $OUTDIR，故先 SetOutPath "$INSTDIR"。
; 快捷方式名按当前语言（$LANGUAGE）经 LangString 解析；注册项为 per-user（HKCU，免管理员），不写 HKLM。
Section -"开始菜单与注册" SecStartMenuReg
  SetOutPath "$INSTDIR"
  CreateDirectory "$SMPROGRAMS\Turboism"
  CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuConfigName).lnk" "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" "-NoProfile -ExecutionPolicy Bypass -File $\"$INSTDIR\configure_turboism.ps1$\""
  CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuUninstallName).lnk" "$INSTDIR\uninstall.exe"
  ; r1: 启动 Cubism 快捷方式（目标 launch-cubism-turboism.bat 由 SecCore 先行写入
  ; $INSTDIR；Start In 取创建时 $OUTDIR，上面已 SetOutPath "$INSTDIR"）
  CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuLaunchName).lnk" "$INSTDIR\launch-cubism-turboism.bat" "" "$INSTDIR\launch-cubism-turboism.bat" 0 SW_SHOWNORMAL "" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "DisplayName" "Turboism"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "DisplayVersion" "${VER}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "Publisher" "Turboism"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism" "DisplayIcon" "$\"$INSTDIR\uninstall.exe$\""
SectionEnd

; 卸载 Section：名字必须恰好为 "Uninstall"（NSIS 特殊命名，代码编入卸载器，
; 不出现在安装器组件页；须为最后一个 Section）
Section "Uninstall"
  ; 先由托管配置器按 manifest 清理 Turboism 自己创建的快捷方式和安装状态。
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -Cleanup' $0
  ${If} $0 != 0
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(ShortcutCleanupFailure)"
    Abort
  ${EndIf}
  ; 清理返回 0 不代表托管状态已清除（如 Wine 内置 PowerShell 返回 0 而未执行脚本）：
  ; 安装状态文件仍存在时失败关闭，不得继续删除注册表或任何载荷。
  ${If} ${FileExists} "$INSTDIR\cubism-installations.json"
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(ShortcutCleanupFailure)"
    Abort
  ${EndIf}
  ; 开始菜单目录 + HKCU 卸载注册项（per-user；与安装时注册表视图一致）
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\Turboism"
  Delete "$SMPROGRAMS\Turboism\$(StartMenuConfigName).lnk"
  Delete "$SMPROGRAMS\Turboism\$(StartMenuUninstallName).lnk"
  Delete "$SMPROGRAMS\Turboism\$(StartMenuLaunchName).lnk"
  RMDir "$SMPROGRAMS\Turboism"
  ; 安装文件
  Delete "$INSTDIR\turboism-agent.jar"
  Delete "$INSTDIR\launch-cubism-turboism.bat"
  Delete "$INSTDIR\launch-cubism-turboism.ps1"
  Delete "$INSTDIR\cubism-launch-common.ps1"
  Delete "$INSTDIR\configure_turboism.ps1"
  ; The configurator removes managed state only after validated shortcut cleanup.
  Delete "$INSTDIR\README*.txt"
  Delete "$INSTDIR\LICENSE"
  Delete "$INSTDIR\uninstall.exe"
  ; 运行时数据目录
  RMDir /r "$INSTDIR\plugins"
  RMDir /r "$INSTDIR\logs"
  RMDir /r "$INSTDIR\state"
  RMDir /r "$INSTDIR\cache"
  ; config.json：按卸载确认页复选框决定（默认勾选 = 删除）
  ${If} $unDeleteConfig == 1
    Delete "$INSTDIR\config.json"
  ${EndIf}
  ; 清理空目录（失败无害：文件被占用/非空时忽略）
  RMDir "$INSTDIR"
SectionEnd

; 卸载确认页 SHOW：创建「同时删除 config.json」复选框（默认勾选）
Function un.ConfirmShow
  StrCpy $unDeleteConfig 1
  StrCpy $unCfgCheckbox 0
  IntOp $unCfgStyle ${WS_CHILD} | ${WS_VISIBLE}
  IntOp $unCfgStyle $unCfgStyle | ${WS_TABSTOP}
  IntOp $unCfgStyle $unCfgStyle | 0x0003        ; BS_AUTOCHECKBOX
  System::Call 'user32::CreateWindowEx(i 0, t "BUTTON", t "$(UnDeleteConfigLabel)", i $unCfgStyle, i 24, i 96, i 330, i 28, i $HWNDPARENT, i 2000, i 0, i 0) i .r$unCfgCheckbox'
  SendMessage $unCfgCheckbox ${BM_SETCHECK} 1 0
FunctionEnd

; 卸载确认页 LEAVE：读取复选框状态到 $unDeleteConfig
Function un.ConfirmLeave
  ${If} $unCfgCheckbox != 0
    SendMessage $unCfgCheckbox ${BM_GETCHECK} 0 0 $0
    ${If} $0 == 1
      StrCpy $unDeleteConfig 1
    ${Else}
      StrCpy $unDeleteConfig 0
    ${EndIf}
  ${EndIf}
FunctionEnd
