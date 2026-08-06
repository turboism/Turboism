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
;     README.txt、plugins/*.jar）；LICENSE 由 ${LICENSE_FILE} 指定。
;
; 插件 Section 通过 ${SEC_<id>} 编译期常量（Section 索引）访问，与声明顺序无关；
; 生成文件内含 SetPluginSectionsSelected / CollectUncheckedPluginIds 两个函数。
; Lite 模式在 ModeLeave 中取消全部插件 Section，其代码体不会执行。

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

; ---------- MUI 设置（中文文案；Unicode true + UTF-8 BOM 源文件） ----------
!define MUI_ABORTWARNING
!define MUI_COMPONENTSPAGE_SMALLDESC

!define MUI_WELCOMEPAGE_TITLE "欢迎安装 Turboism"
!define MUI_WELCOMEPAGE_TEXT "本向导将安装 Turboism —— Live2D Cubism 编辑器的增强运行时。$\r$\n$\r$\n安装为免管理员模式，不会修改 Cubism 安装目录。$\r$\n$\r$\n安装完成后，请通过安装目录中的 launch-cubism-turboism.bat 启动 Cubism 编辑器。$\r$\n$\r$\n点击“下一步”继续。"

!define MUI_LICENSEPAGE_TEXT_TOP "请阅读以下许可协议。滚动查看全文："

!define MUI_DIRECTORYPAGE_TEXT_TOP "Turboism 将安装到以下目录（Turboism home）："

!define MUI_FINISHPAGE_TITLE "安装完成"
!define MUI_FINISHPAGE_TEXT "Turboism 已安装到：$\r$\n$INSTDIR$\r$\n$\r$\n启动方式：双击 launch-cubism-turboism.bat（自动探测 Cubism 安装目录）。$\r$\n调整插件开关：运行 configure_turboism.ps1。$\r$\n$\r$\n详细说明见安装目录中的 README.txt。"

; ---------- 页面流程：Welcome → License → 模式选择 → Components(仅 Full) → Directory → InstFiles → Finish ----------
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"
Page custom ModeCreate ModeLeave
!define MUI_PAGE_CUSTOMFUNCTION_PRE ComponentsPre
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_LANGUAGE "SimpChinese"

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

; ---------- 初始化 ----------
Function .onInit
  StrCpy $Mode 1
  StrCpy $INSTDIR "$LOCALAPPDATA\Turboism"
FunctionEnd

; ---------- 模式选择页（nsDialogs） ----------
Function ModeCreate
  nsDialogs::Create 1018
  Pop $ModeDialog
  ${If} $ModeDialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "请选择安装模式："
  Pop $0
  ${NSD_CreateRadioButton} 0 34u 100% 14u "完整安装（Full）—— 安装全部插件，可在下一步选择禁用部分插件（默认）"
  Pop $FullRadio
  ${NSD_CreateRadioButton} 0 52u 100% 14u "精简安装（Lite）—— 仅安装核心运行时，不安装任何插件"
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
  File "${STAGING_DIR}/README.txt"
  File "${LICENSE_FILE}"
SectionEnd

; 插件 Section + 描述 + 选择状态函数（由 assemble-release.sh 生成，勿手改）
!include "plugin-sections.nsh"

Section "-写入配置" SecConfig
  ; 收集本次未勾选的插件 id（Full 模式）
  StrCpy $uncheckedPluginIds ""
  ${If} $Mode == 1
    Call CollectUncheckedPluginIds
  ${EndIf}
  ; 读取既有 config.json 的 disabledPlugins（合并时保留）
  StrCpy $existingDisabled ""
  Call ReadExistingDisabledPlugins
  ; 合并 + 排序 + 写回（从模板重建；worktreeId/pluginDirs 固定覆盖）
  Call MergeAndWriteConfig
SectionEnd

; ---------- 配置合并 ----------
; 语义（与 SPEC.md 一致）：保留既有 disabledPlugins 并合并本次未勾选插件，
; worktreeId 覆盖为 turboism-runtime，pluginDirs 覆盖为 ["plugins"]。
; 其它字段（logLevel/hooks 等）不保留，由运行时默认值补全；
; 需要完整保留既有配置的字段时请使用 configure_turboism.ps1。
; 注意：config.json 内容为纯 ASCII，FileWrite（Unicode 安装器下按 ACP 转换）安全。

; 输入: $0 = ';' 分隔列表；输出: $0 = 首段, $1 = 剩余
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
      StrCpy $1 "$0" $pos
      IntOp $next $pos + 1
      StrCpy $0 "$0" "" $next
      ${ExitDo}
    ${EndIf}
    IntOp $pos $pos + 1
  ${Loop}
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
; 输出 JSON 写入 $INSTDIR\config.json
Function MergeAndWriteConfig
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
    StrCpy $json '$json,"disabledPlugins":["'
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
    StrCpy $json '$json"]'
  ${EndIf}
  StrCpy $json '$json}$\r$\n'
  ; 写入
  FileOpen $configHandle "$INSTDIR\config.json" w
  ${If} $configHandle == ""
    MessageBox MB_ICONSTOP "无法写入 config.json：$INSTDIR\config.json"
    Abort
  ${EndIf}
  FileWrite $configHandle $json
  FileClose $configHandle
FunctionEnd
