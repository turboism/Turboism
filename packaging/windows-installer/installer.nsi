; -*- coding: utf-8 -*-
; Turboism Windows 安装器 — NSIS MUI2（Unicode）
;
; 构建（由 assemble-release.sh 调用，也可手动）：
;   makensis -WX -DVER=<ver> -DSTAGING_DIR=<abs> -DGENERATED_DIR=<abs> -DOUT_DIR=<abs> installer.nsi
;
; 依赖：
;   - plugin-sections.nsh 由 assemble-release.sh 从插件 jar 的
;     META-INF/turboism/plugin.json 生成（每个插件一个 Section）。
;   - ${STAGING_DIR} 为 staging 目录（turboism-agent.jar、launch 脚本、
;     README.txt / README.zh.txt / README.ja.txt、plugins/*.jar）；LICENSE 由 ${LICENSE_FILE} 指定。
;
; 插件 Section 通过 ${SEC_<id>} 编译期常量（Section 索引）访问，与声明顺序无关；
; 生成文件内含隐藏载荷 Section（$Mode==1 时安装全部插件 JAR）与
; SetPluginSectionsSelected / CollectUncheckedPluginIds 两个函数。

Unicode true

!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "Sections.nsh"
!include "WinMessages.nsh"

; ---------- 编译期参数（assemble-release.sh 传入绝对路径；缺省值供独立编译） ----------
; 注意：makensis 将脚本内相对路径解析为相对于脚本所在目录，
; 因此缺省值用从 packaging/windows-installer/ 出发的相对路径。
!ifndef VER
  !define VER "0.0.0-dev"
!endif
!ifndef STAGING_DIR
  !define STAGING_DIR "../../build/windows-installer/staging"
!endif
!ifndef GENERATED_DIR
  !define GENERATED_DIR "../../build/windows-installer/generated"
!endif
!ifndef OUT_DIR
  !define OUT_DIR "../../build/windows-installer/dist"
!endif
!ifndef LICENSE_FILE
  !define LICENSE_FILE "../../LICENSE"
!endif
!ifndef EULA_DIR
  !define EULA_DIR "../eula"
!endif
!ifndef ICON_FILE
  !define ICON_FILE "assets/turboism.ico"
!endif

; ---------- 基本属性 ----------
Name "Turboism ${VER}"
OutFile "${OUT_DIR}/TurboismInstaller-${VER}.exe"
Icon "${ICON_FILE}"
UninstallIcon "${ICON_FILE}"
InstallDir "$LOCALAPPDATA\Turboism"
RequestExecutionLevel user
SetCompressor /SOLID lzma

; NSIS dialog units derive their pixel size from this font. Raising the stock
; 8-point wizard font to 12 points scales both the main window and every native
; or nsDialogs page together, instead of enlarging only the outer frame.
SetFont "MS Shell Dlg" 12

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
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"

!define MUI_WELCOMEPAGE_TITLE "$(TurboismWelcomeTitle)"
!define MUI_WELCOMEPAGE_TEXT "$(TurboismWelcomeText)"

!define MUI_LICENSEPAGE_TEXT_TOP "$(LicenseTopText)"
!define MUI_LICENSEPAGE_TEXT_BOTTOM "$(LicenseBottomText)"

!define MUI_DIRECTORYPAGE_TEXT_TOP "$(DirectoryTopText)"

!define MUI_FINISHPAGE_TITLE "$(FinishTitleText)"
!define MUI_FINISHPAGE_TEXT "$(FinishBodyText)"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchTurboism
!define MUI_FINISHPAGE_RUN_TEXT "$(FinishLaunchTurboismText)"
!define MUI_FINISHPAGE_SHOWREADME
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION OpenInstallDirectory
!define MUI_FINISHPAGE_SHOWREADME_TEXT "$(FinishOpenFolderText)"

; 安装前哈希计划对应的条件解压函数（由 assemble-release.sh 生成）。
!include "${GENERATED_DIR}/payload-extract.nsh"

; ---------- 页面流程：Welcome → MIT License → EULA 正文 → 四项确认 → 模式 → Components → Graal → Directory → Cubism 扫描 → 启动选项 → 安装 → Finish ----------
!insertmacro MUI_PAGE_WELCOME
!define MUI_LICENSEPAGE_CHECKBOX
!define MUI_LICENSEPAGE_CHECKBOX_TEXT "$(LicenseAcceptText)"
!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"
!define MUI_LICENSEPAGE_TEXT_TOP "$(EulaTopText)"
!define MUI_LICENSEPAGE_BUTTON "$(EulaAgreeButtonText)"
!insertmacro MUI_PAGE_LICENSE "$(EulaFile)"
Page custom EulaAcknowledgementsCreate EulaAcknowledgementsLeave
Page custom ModeCreate ModeLeave
!define MUI_PAGE_CUSTOMFUNCTION_PRE ComponentsPre
!insertmacro MUI_PAGE_COMPONENTS
Page custom GraalCreate GraalLeave
!insertmacro MUI_PAGE_DIRECTORY
Page custom CubismDiscoveryCreate CubismDiscoveryLeave
Page custom LaunchOptionsCreate LaunchOptionsLeave
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

; ---------- 语言：四语言共存，运行时由 MUI 语言选择对话框决定 $LANGUAGE；
; 对话框出现前 $LANGUAGE 已由 NSIS 按系统区域自动匹配，因此默认预选系统语言，
; 用户可显式改为其它语言。首个语言为无匹配时的缺省。 ----------
;
; MUI_LANGDLL_ALLLANGUAGES：语言对话框列出全部已注册语言，不因系统 codepage
; 而隐藏 CJK/韩语，确保四种语言在任何区域设置下都可选。
!define MUI_LANGDLL_ALLLANGUAGES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "Japanese"
!insertmacro MUI_LANGUAGE "Korean"

; EULA 正文只有 en/zh-Hans/ja 三份译文；韩语界面回退英文正文（见 packaging/eula/），
; 简体中文仍是权威文本。不要为韩语伪造第四份法律文本。
LicenseLangString EulaFile ${LANG_ENGLISH} "${EULA_DIR}/EULA.en.txt"
LicenseLangString EulaFile ${LANG_SIMPCHINESE} "${EULA_DIR}/EULA.zh-Hans.txt"
LicenseLangString EulaFile ${LANG_JAPANESE} "${EULA_DIR}/EULA.ja.txt"
LicenseLangString EulaFile ${LANG_KOREAN} "${EULA_DIR}/EULA.en.txt"

; ---------- 自定义文案 LangString（en/zh/ja/ko） ----------
LangString TurboismWelcomeTitle ${LANG_ENGLISH} "Welcome to Turboism Setup"
LangString TurboismWelcomeTitle ${LANG_SIMPCHINESE} "欢迎安装 Turboism"
LangString TurboismWelcomeTitle ${LANG_JAPANESE} "Turboism セットアップへようこそ"
LangString TurboismWelcomeTitle ${LANG_KOREAN} "Turboism 설치 프로그램에 오신 것을 환영합니다"

LangString TurboismWelcomeText ${LANG_ENGLISH} "This wizard installs Turboism, an enhanced runtime for Live2D Cubism Editor.$\r$\n$\r$\nInstallation is per-user. On the final options page you may create Turboism shortcuts and, only if explicitly selected, back up and modify official Cubism startup BAT files.$\r$\nCurrently supported versions: 5.2.03, 5.3.02, 5.3.03$\r$\n$\r$\nClick Next to continue."
LangString TurboismWelcomeText ${LANG_SIMPCHINESE} "本向导将安装 Turboism —— Live2D Cubism 编辑器的增强运行时。$\r$\n$\r$\n安装为免管理员模式。在最后的选项页面中，您可以创建 Turboism 快捷方式；仅在明确勾选时，安装器才会备份并修改 Cubism 官方启动 BAT。$\r$\n当前支持版本：5.2.03, 5.3.02, 5.3.03$\r$\n$\r$\n点击“下一步”继续。"
LangString TurboismWelcomeText ${LANG_JAPANESE} "このウィザードは Live2D Cubism Editor 用の拡張ランタイム Turboism をインストールします。$\r$\n$\r$\nインストールはユーザー単位です。最後のオプション画面で Turboism ショートカットを作成でき、明示的に選択した場合のみ Cubism 公式起動 BAT をバックアップして変更します。$\r$\n現在対応しているバージョン：5.2.03, 5.3.02, 5.3.03$\r$\n$\r$\n「次へ」をクリックして続行します。"
LangString TurboismWelcomeText ${LANG_KOREAN} "이 마법사는 Live2D Cubism Editor용 확장 런타임인 Turboism을 설치합니다.$\r$\n$\r$\n설치는 사용자 단위로 이루어집니다. 마지막 옵션 페이지에서 Turboism 바로 가기를 만들 수 있으며, 명시적으로 선택한 경우에만 Cubism 공식 시작 BAT 파일을 백업하고 수정합니다.$\r$\n현재 지원 버전: 5.2.03, 5.3.02, 5.3.03$\r$\n$\r$\n계속하려면 [다음]을 클릭하세요."

LangString LicenseTopText ${LANG_ENGLISH} "Please review the MIT License before installing Turboism. Scroll down to see the full text:"
LangString LicenseTopText ${LANG_SIMPCHINESE} "请在安装 Turboism 前阅读 MIT License。滚动查看全文："
LangString LicenseTopText ${LANG_JAPANESE} "Turboism をインストールする前に MIT License をお読みください。全文を表示するには下へスクロールしてください："
LangString LicenseTopText ${LANG_KOREAN} "Turboism을 설치하기 전에 MIT License를 확인하세요. 전체 내용을 보려면 아래로 스크롤하세요:"
LangString LicenseBottomText ${LANG_ENGLISH} "If you accept the MIT License, select the checkbox below to continue."
LangString LicenseBottomText ${LANG_SIMPCHINESE} "如果您同意 MIT License，请勾选下方复选框后继续。"
LangString LicenseBottomText ${LANG_JAPANESE} "MIT License に同意する場合は、下のチェックボックスを選択して続行してください。"
LangString LicenseBottomText ${LANG_KOREAN} "MIT License에 동의하면 아래 확인란을 선택하여 계속하세요."
LangString LicenseAcceptText ${LANG_ENGLISH} "I accept the MIT License"
LangString LicenseAcceptText ${LANG_SIMPCHINESE} "我同意 MIT License"
LangString LicenseAcceptText ${LANG_JAPANESE} "MIT License に同意します"
LangString LicenseAcceptText ${LANG_KOREAN} "MIT License에 동의합니다"

LangString EulaTopText ${LANG_ENGLISH} "Review the full Turboism End User Runtime Statement and Disclaimer below. The four required acknowledgements are on the next page:"
LangString EulaTopText ${LANG_SIMPCHINESE} "请阅读下方完整的 Turboism 最终用户运行声明与免责声明。四项必选确认位于下一页："
LangString EulaTopText ${LANG_JAPANESE} "以下の Turboism エンドユーザー運用声明および免責事項の全文を確認してください。必須の4項目の確認は次のページにあります："
LangString EulaTopText ${LANG_KOREAN} "아래에서 Turboism 최종 사용자 실행 고지 및 면책 조항 전문을 확인하세요. 필수 확인 4개 항목은 다음 페이지에 있습니다:"
LangString EulaAgreeButtonText ${LANG_ENGLISH} "I &Agree"
LangString EulaAgreeButtonText ${LANG_SIMPCHINESE} "我同意(&I)"
LangString EulaAgreeButtonText ${LANG_JAPANESE} "同意する(&A)"
LangString EulaAgreeButtonText ${LANG_KOREAN} "동의합니다(&A)"
LangString EulaAcknowledgementsTitle ${LANG_ENGLISH} "Required acknowledgements"
LangString EulaAcknowledgementsTitle ${LANG_SIMPCHINESE} "必选确认"
LangString EulaAcknowledgementsTitle ${LANG_JAPANESE} "必須の確認"
LangString EulaAcknowledgementsTitle ${LANG_KOREAN} "필수 확인 항목"
LangString EulaAcknowledgementsSubtitle ${LANG_ENGLISH} "Check all four independent acknowledgements to continue."
LangString EulaAcknowledgementsSubtitle ${LANG_SIMPCHINESE} "继续安装前，请分别勾选全部四项确认。"
LangString EulaAcknowledgementsSubtitle ${LANG_JAPANESE} "続行するには、4項目すべてを個別に選択してください。"
LangString EulaAcknowledgementsSubtitle ${LANG_KOREAN} "계속하려면 4개 항목을 각각 모두 선택하세요."
LangString EulaAck1 ${LANG_ENGLISH} "I confirm that Turboism is an independent third-party project and not an official Live2D product."
LangString EulaAck1 ${LANG_SIMPCHINESE} "我确认 Turboism 是独立第三方项目，并非 Live2D 官方产品。"
LangString EulaAck1 ${LANG_JAPANESE} "Turboism は独立した第三者プロジェクトであり、Live2D の公式製品ではないことを確認します。"
LangString EulaAck1 ${LANG_KOREAN} "Turboism은 독립적인 서드파티 프로젝트이며 Live2D의 공식 제품이 아님을 확인합니다."
LangString EulaAck2 ${LANG_ENGLISH} "I confirm that Cubism still requires valid legal authorization; Turboism does not provide, replace, or bypass Cubism license verification."
LangString EulaAck2 ${LANG_SIMPCHINESE} "我确认使用 Cubism 仍需合法、有效的授权；Turboism 不提供、替代或绕过 Cubism 的许可校验。"
LangString EulaAck2 ${LANG_JAPANESE} "Cubism の使用には引き続き合法かつ有効な許諾が必要であり、Turboism は Cubism のライセンス確認を提供、代替、回避しないことを確認します。"
LangString EulaAck2 ${LANG_KOREAN} "Cubism 사용에는 여전히 적법하고 유효한 허가가 필요하며, Turboism은 Cubism의 라이선스 검증을 제공·대체·우회하지 않음을 확인합니다."
LangString EulaAck3 ${LANG_ENGLISH} "I confirm that I understand: plugin, script, MCP, API, and automation operations that I start or authorize may modify, overwrite, or delete project content, and I will keep an independent backup."
LangString EulaAck3 ${LANG_SIMPCHINESE} "我确认我已理解：由我启动或授权的插件、脚本、MCP、API 和自动化操作可能修改、覆盖或删除工程内容，并将自行保留独立备份。"
LangString EulaAck3 ${LANG_JAPANESE} "自分が開始または許可したプラグイン、スクリプト、MCP、API、自動化の操作がプロジェクト内容を変更、上書き、削除する可能性を理解し、独立したバックアップを保持することを確認します。"
LangString EulaAck3 ${LANG_KOREAN} "내가 시작하거나 허용한 플러그인, 스크립트, MCP, API 및 자동화 작업이 프로젝트 내용을 변경·덮어쓰기·삭제할 수 있음을 이해하며, 독립적인 백업을 보관하겠습니다."
LangString EulaAck4 ${LANG_ENGLISH} "I confirm that I understand: Turboism is an open-source project provided as is, with no guarantee of continued compatibility, freedom from errors, or successful recovery."
LangString EulaAck4 ${LANG_SIMPCHINESE} "我确认我已理解：Turboism 是按现状提供的开源项目，不保证持续兼容、无错误或成功恢复。"
LangString EulaAck4 ${LANG_JAPANESE} "Turboism は現状有姿で提供されるオープンソースプロジェクトであり、継続的な互換性、無エラー、復元の成功は保証されないことを理解したことを確認します。"
LangString EulaAck4 ${LANG_KOREAN} "Turboism은 있는 그대로 제공되는 오픈 소스 프로젝트이며, 지속적인 호환성, 무오류 또는 성공적인 복구를 보장하지 않음을 이해합니다."
LangString EulaRequired ${LANG_ENGLISH} "Check all four acknowledgements before continuing."
LangString EulaRequired ${LANG_SIMPCHINESE} "继续安装前必须勾选全部四项确认。"
LangString EulaRequired ${LANG_JAPANESE} "続行する前に4項目すべてを選択してください。"
LangString EulaRequired ${LANG_KOREAN} "계속하기 전에 4개 확인 항목을 모두 선택해야 합니다."

LangString DirectoryTopText ${LANG_ENGLISH} "Turboism will be installed to the following directory (Turboism home):"
LangString DirectoryTopText ${LANG_SIMPCHINESE} "Turboism 将安装到以下目录（Turboism home）："
LangString DirectoryTopText ${LANG_JAPANESE} "Turboism は次のディレクトリ（Turboism home）にインストールされます："
LangString DirectoryTopText ${LANG_KOREAN} "Turboism은 다음 디렉터리(Turboism home)에 설치됩니다:"

LangString FinishTitleText ${LANG_ENGLISH} "Installation Complete"
LangString FinishTitleText ${LANG_SIMPCHINESE} "安装完成"
LangString FinishTitleText ${LANG_JAPANESE} "インストール完了"
LangString FinishTitleText ${LANG_KOREAN} "설치 완료"

LangString FinishBodyText ${LANG_ENGLISH} "Turboism has been installed to:$\r$\n$INSTDIR$\r$\n$\r$\nYour selected activation paths have been applied. Start-menu shortcuts are independent from official-BAT integration. BAT integration uses verified backups and can be restored during uninstall.$\r$\n$\r$\nRun configure_turboism.ps1 to manage Cubism installations and plugin settings."
LangString FinishBodyText ${LANG_SIMPCHINESE} "Turboism 已安装到：$\r$\n$INSTDIR$\r$\n$\r$\n已应用您选择的激活路径。开始菜单快捷方式与官方 BAT 集成彼此独立；BAT 集成使用经过校验的备份，并可在卸载时恢复。$\r$\n$\r$\n可运行 configure_turboism.ps1 管理 Cubism 安装与插件设置。"
LangString FinishBodyText ${LANG_JAPANESE} "Turboism は次の場所にインストールされました：$\r$\n$INSTDIR$\r$\n$\r$\n選択した有効化経路を適用しました。スタートメニューのショートカットと公式 BAT の統合は独立しています。BAT 統合は検証済みバックアップを使用し、アンインストール時に復元できます。$\r$\n$\r$\nconfigure_turboism.ps1 で Cubism インストールとプラグイン設定を管理できます。"
LangString FinishBodyText ${LANG_KOREAN} "Turboism이 다음 위치에 설치되었습니다:$\r$\n$INSTDIR$\r$\n$\r$\n선택한 활성화 경로를 적용했습니다. 시작 메뉴 바로 가기는 공식 BAT 통합과 별개입니다. BAT 통합은 검증된 백업을 사용하며 제거 시 복원할 수 있습니다.$\r$\n$\r$\nconfigure_turboism.ps1을 실행하여 Cubism 설치와 플러그인 설정을 관리할 수 있습니다."
LangString FinishLaunchTurboismText ${LANG_ENGLISH} "Launch Turboism"
LangString FinishLaunchTurboismText ${LANG_SIMPCHINESE} "启动 Turboism"
LangString FinishLaunchTurboismText ${LANG_JAPANESE} "Turboism を起動"
LangString FinishLaunchTurboismText ${LANG_KOREAN} "Turboism 시작"
LangString FinishOpenFolderText ${LANG_ENGLISH} "Open the Turboism installation folder"
LangString FinishOpenFolderText ${LANG_SIMPCHINESE} "打开 Turboism 安装目录"
LangString FinishOpenFolderText ${LANG_JAPANESE} "Turboism インストールフォルダーを開く"
LangString FinishOpenFolderText ${LANG_KOREAN} "Turboism 설치 폴더 열기"

LangString UnConfirmTextTop ${LANG_ENGLISH} "Turboism will be uninstalled from the following folder:$\r$\nClick Yes to remove it:"
LangString UnConfirmTextTop ${LANG_SIMPCHINESE} "Turboism 将从以下文件夹卸载：$\r$\n点击“是”开始卸载。"
LangString UnConfirmTextTop ${LANG_JAPANESE} "Turboism は次のフォルダーからアンインストールされます：$\r$\n「はい」をクリックすると削除します："
LangString UnConfirmTextTop ${LANG_KOREAN} "Turboism이 다음 폴더에서 제거됩니다:$\r$\n[예]를 클릭하면 제거합니다:"

LangString UnKeepConfigLabel ${LANG_ENGLISH} "Keep config.json (user configuration)"
LangString UnKeepConfigLabel ${LANG_SIMPCHINESE} "保留 config.json（用户配置）"
LangString UnKeepConfigLabel ${LANG_JAPANESE} "config.json（ユーザー設定）を保持する"
LangString UnKeepConfigLabel ${LANG_KOREAN} "config.json(사용자 설정) 유지"

LangString ModePageTitle ${LANG_ENGLISH} "Select the installation mode:"
LangString ModePageTitle ${LANG_SIMPCHINESE} "请选择安装模式："
LangString ModePageTitle ${LANG_JAPANESE} "インストールモードを選択してください："
LangString ModePageTitle ${LANG_KOREAN} "설치 모드를 선택하세요:"

LangString ModeFullLabel ${LANG_ENGLISH} "Full installation (Full) — installs all plugins; you can disable some plugins on the next page (default)"
LangString ModeFullLabel ${LANG_SIMPCHINESE} "完整安装（Full）—— 安装全部插件，可在下一步选择禁用部分插件（默认）"
LangString ModeFullLabel ${LANG_JAPANESE} "フルインストール（Full）—— すべてのプラグインをインストールします。次のページで一部のプラグインを無効化できます（既定）"
LangString ModeFullLabel ${LANG_KOREAN} "전체 설치(Full) — 모든 플러그인을 설치하며, 다음 페이지에서 일부 플러그인을 사용하지 않도록 선택할 수 있습니다(기본값)"

LangString ModeLiteLabel ${LANG_ENGLISH} "Lite installation (Lite) — installs only the core runtime, no plugins"
LangString ModeLiteLabel ${LANG_SIMPCHINESE} "精简安装（Lite）—— 仅安装核心运行时，不安装任何插件"
LangString ModeLiteLabel ${LANG_JAPANESE} "ライトインストール（Lite）—— コアランタイムのみインストールし、プラグインはインストールしません"
LangString ModeLiteLabel ${LANG_KOREAN} "간편 설치(Lite) — 코어 런타임만 설치하며 플러그인은 설치하지 않습니다"


LangString ManagedGraalLabel ${LANG_ENGLISH} "Download and install Turboism-managed GraalVM (optional; about 326 MiB)"
LangString ManagedGraalLabel ${LANG_SIMPCHINESE} "下载并安装 Turboism 托管的 GraalVM（可选；约 326 MiB）"
LangString ManagedGraalLabel ${LANG_JAPANESE} "Turboism 管理の GraalVM をダウンロードしてインストール（任意、約 326 MiB）"
LangString ManagedGraalLabel ${LANG_KOREAN} "Turboism 관리형 GraalVM 다운로드 및 설치(선택 사항, 약 326 MiB)"
LangString ManagedGraalHelp ${LANG_ENGLISH} "Downloads the pinned official GraalVM archive, verifies its size, SHA-256 and release metadata, then extracts it and saves its path in Turboism configuration. No preinstalled Java is needed. No download occurs unless selected."
LangString ManagedGraalHelp ${LANG_SIMPCHINESE} "下载 GraalVM 官方固定版本，校验大小、SHA-256 和发布元数据后解压，并将路径写入 Turboism 配置。无需预先安装 Java，仅在勾选后下载。"
LangString ManagedGraalHelp ${LANG_JAPANESE} "GraalVM 公式の固定版をダウンロードし、サイズ、SHA-256、リリース情報を検証して展開し、パスを Turboism 設定に保存します。Java の事前インストールは不要です。選択時のみダウンロードします。"
LangString ManagedGraalHelp ${LANG_KOREAN} "공식 GraalVM 고정 버전을 다운로드하고 크기, SHA-256 및 릴리스 정보를 검증한 후 압축을 풀고 경로를 Turboism 설정에 저장합니다. Java를 미리 설치할 필요가 없으며 선택한 경우에만 다운로드합니다."
LangString ManagedGraalInstallError ${LANG_ENGLISH} "GraalVM was not installed. Review $INSTDIR\logs\installer\managed-graal-install.log and check GitHub access, free disk space, and write access to the installation directory and configuration before retrying."
LangString ManagedGraalInstallError ${LANG_SIMPCHINESE} "GraalVM 未完成安装。请查看 $INSTDIR\logs\installer\managed-graal-install.log，检查 GitHub 访问、剩余磁盘空间及安装目录和配置文件的写入权限后重试。"
LangString ManagedGraalInstallError ${LANG_JAPANESE} "GraalVM のインストールが完了しませんでした。$INSTDIR\logs\installer\managed-graal-install.log を参照し、GitHub への接続、空き容量、インストール先と設定ファイルの書き込み権限を確認して再試行してください。"
LangString ManagedGraalInstallError ${LANG_KOREAN} "GraalVM 설치가 완료되지 않았습니다. $INSTDIR\logs\installer\managed-graal-install.log를 확인하고 GitHub 연결, 디스크 여유 공간, 설치 디렉터리와 설정 파일의 쓰기 권한을 확인한 후 다시 시도하세요."
LangString ManagedGraalStarting ${LANG_ENGLISH} "Installing Turboism-managed GraalVM. Progress is also saved to $INSTDIR\logs\installer\managed-graal-install.log."
LangString ManagedGraalStarting ${LANG_SIMPCHINESE} "正在安装 Turboism 托管的 GraalVM。进度也会保存到 $INSTDIR\logs\installer\managed-graal-install.log。"
LangString ManagedGraalStarting ${LANG_JAPANESE} "Turboism 管理の GraalVM をインストールしています。進捗は $INSTDIR\logs\installer\managed-graal-install.log にも保存されます。"
LangString ManagedGraalStarting ${LANG_KOREAN} "Turboism 관리형 GraalVM을 설치하는 중입니다. 진행 상황은 $INSTDIR\logs\installer\managed-graal-install.log에도 저장됩니다."

LangString GraalPageTitle ${LANG_ENGLISH} "Improve performance with GraalVM"
LangString GraalPageTitle ${LANG_SIMPCHINESE} "使用 GraalVM 提升性能"
LangString GraalPageTitle ${LANG_JAPANESE} "GraalVM でパフォーマンスを向上"
LangString GraalPageTitle ${LANG_KOREAN} "GraalVM으로 성능 향상"
LangString GraalPageDescription ${LANG_ENGLISH} "Turboism can use a pinned private GraalVM runtime to improve script and plugin performance. You can install it now or download it later from the Turboism configurator."
LangString GraalPageDescription ${LANG_SIMPCHINESE} "Turboism 可使用固定版本的私有 GraalVM 运行时提升脚本与插件性能。您可以立即安装，也可以稍后在 Turboism 配置器中下载。"
LangString GraalPageDescription ${LANG_JAPANESE} "Turboism は固定された専用 GraalVM ランタイムでスクリプトとプラグインの性能を向上できます。今すぐインストールするか、Turboism 設定から後でダウンロードできます。"
LangString GraalPageDescription ${LANG_KOREAN} "Turboism은 고정된 전용 GraalVM 런타임으로 스크립트와 플러그인 성능을 향상할 수 있습니다. 지금 설치하거나 나중에 Turboism 설정에서 다운로드할 수 있습니다."
LangString GraalNowChoice ${LANG_ENGLISH} "Download and install now (recommended)"
LangString GraalNowChoice ${LANG_SIMPCHINESE} "立即下载并安装（推荐）"
LangString GraalNowChoice ${LANG_JAPANESE} "今すぐダウンロードしてインストール（推奨）"
LangString GraalNowChoice ${LANG_KOREAN} "지금 다운로드하여 설치(권장)"
LangString GraalLaterChoice ${LANG_ENGLISH} "Install later"
LangString GraalLaterChoice ${LANG_SIMPCHINESE} "稍后安装"
LangString GraalLaterChoice ${LANG_JAPANESE} "後でインストール"
LangString GraalLaterChoice ${LANG_KOREAN} "나중에 설치"
LangString GraalProgressHint ${LANG_ENGLISH} "When downloading, a separate progress window shows downloaded/total bytes, transfer rate, and a Cancel button."
LangString GraalProgressHint ${LANG_SIMPCHINESE} "下载时将显示独立进度窗口，包括已下载/总字节数、传输速度和“取消”按钮。"
LangString GraalProgressHint ${LANG_JAPANESE} "ダウンロード中は別の進捗画面に、ダウンロード済み/合計バイト数、転送速度、キャンセルボタンが表示されます。"
LangString GraalProgressHint ${LANG_KOREAN} "다운로드할 때 다운로드/전체 바이트, 전송 속도 및 [취소] 버튼이 표시되는 별도 진행 창이 열립니다."

LangString CubismDiscoveryTitle ${LANG_ENGLISH} "Cubism installations"
LangString CubismDiscoveryTitle ${LANG_SIMPCHINESE} "Cubism 安装"
LangString CubismDiscoveryTitle ${LANG_JAPANESE} "Cubism インストール"
LangString CubismDiscoveryTitle ${LANG_KOREAN} "Cubism 설치"
LangString CubismDiscoveryScanning ${LANG_ENGLISH} "Scanning for installed Cubism editors…"
LangString CubismDiscoveryScanning ${LANG_SIMPCHINESE} "正在扫描已安装的 Cubism 编辑器……"
LangString CubismDiscoveryScanning ${LANG_JAPANESE} "インストール済みの Cubism Editor をスキャンしています…"
LangString CubismDiscoveryScanning ${LANG_KOREAN} "설치된 Cubism Editor를 스캔하는 중…"
LangString CubismDiscoveryComplete ${LANG_ENGLISH} "Scan complete: $CubismDiscoverySupported supported; $CubismDiscoveryOther unsupported or invalid. Supported installations will be revalidated and configured after installation."
LangString CubismDiscoveryComplete ${LANG_SIMPCHINESE} "扫描完成：支持 $CubismDiscoverySupported 个；不支持或无效 $CubismDiscoveryOther 个。安装后会重新校验并配置受支持的安装。"
LangString CubismDiscoveryComplete ${LANG_JAPANESE} "スキャン完了：対応 $CubismDiscoverySupported 件、未対応または不正 $CubismDiscoveryOther 件。インストール後に再検証して設定します。"
LangString CubismDiscoveryComplete ${LANG_KOREAN} "스캔 완료: 지원 $CubismDiscoverySupported개, 미지원 또는 잘못됨 $CubismDiscoveryOther개. 지원되는 설치는 설치 후 다시 검증하여 구성합니다."
LangString CubismDiscoveryNone ${LANG_ENGLISH} "No supported Cubism installation was found. Turboism can still be installed and configured later."
LangString CubismDiscoveryNone ${LANG_SIMPCHINESE} "未找到受支持的 Cubism 安装。仍可安装 Turboism，并在之后进行配置。"
LangString CubismDiscoveryNone ${LANG_JAPANESE} "対応する Cubism インストールが見つかりません。Turboism はそのままインストールし、後で設定できます。"
LangString CubismDiscoveryNone ${LANG_KOREAN} "지원되는 Cubism 설치를 찾지 못했습니다. Turboism은 그대로 설치할 수 있으며 나중에 구성할 수 있습니다."
LangString CubismDiscoveryFailed ${LANG_ENGLISH} "Cubism scanning did not complete. Turboism can still be installed and configured later."
LangString CubismDiscoveryFailed ${LANG_SIMPCHINESE} "Cubism 扫描未完成。仍可安装 Turboism，并在之后进行配置。"
LangString CubismDiscoveryFailed ${LANG_JAPANESE} "Cubism のスキャンを完了できませんでした。Turboism はそのままインストールし、後で設定できます。"
LangString CubismDiscoveryFailed ${LANG_KOREAN} "Cubism 스캔이 완료되지 않았습니다. Turboism은 그대로 설치할 수 있으며 나중에 구성할 수 있습니다."
LangString CubismDiscoveryTimeout ${LANG_ENGLISH} "Cubism scanning timed out. Turboism can still be installed and configured later."
LangString CubismDiscoveryTimeout ${LANG_SIMPCHINESE} "Cubism 扫描超时。仍可安装 Turboism，并在之后进行配置。"
LangString CubismDiscoveryTimeout ${LANG_JAPANESE} "Cubism のスキャンがタイムアウトしました。Turboism はそのままインストールし、後で設定できます。"
LangString CubismDiscoveryTimeout ${LANG_KOREAN} "Cubism 스캔 시간이 초과되었습니다. Turboism은 그대로 설치할 수 있으며 나중에 구성할 수 있습니다."

LangString LaunchOptionsTitle ${LANG_ENGLISH} "Choose normal launch integration"
LangString LaunchOptionsTitle ${LANG_SIMPCHINESE} "选择常规启动集成"
LangString LaunchOptionsTitle ${LANG_JAPANESE} "通常起動の統合を選択"
LangString LaunchOptionsTitle ${LANG_KOREAN} "일반 시작 통합 선택"
LangString StartMenuOption ${LANG_ENGLISH} "Create Turboism Start-menu shortcuts (recommended)"
LangString StartMenuOption ${LANG_SIMPCHINESE} "创建 Turboism 开始菜单快捷方式（推荐）"
LangString StartMenuOption ${LANG_JAPANESE} "Turboism のスタートメニューショートカットを作成（推奨）"
LangString StartMenuOption ${LANG_KOREAN} "Turboism 시작 메뉴 바로 가기 만들기(권장)"
LangString DesktopShortcutOption ${LANG_ENGLISH} "Create a Turboism desktop shortcut"
LangString DesktopShortcutOption ${LANG_SIMPCHINESE} "创建 Turboism 桌面快捷方式"
LangString DesktopShortcutOption ${LANG_JAPANESE} "Turboism のデスクトップショートカットを作成"
LangString DesktopShortcutOption ${LANG_KOREAN} "Turboism 바탕 화면 바로 가기 만들기"
LangString BatIntegrationOption ${LANG_ENGLISH} "Modify the selected official Cubism startup BAT files so existing Cubism shortcuts load Turboism"
LangString BatIntegrationOption ${LANG_SIMPCHINESE} "修改所选 Cubism 官方启动 BAT，使现有 Cubism 快捷方式加载 Turboism"
LangString BatIntegrationOption ${LANG_JAPANESE} "選択した Cubism 公式起動 BAT を変更し、既存の Cubism ショートカットで Turboism を読み込む"
LangString BatIntegrationOption ${LANG_KOREAN} "선택한 Cubism 공식 시작 BAT를 수정하여 기존 Cubism 바로 가기에서 Turboism을 로드하도록 합니다"
LangString NoLaunchWarning ${LANG_ENGLISH} "All normal launch paths are disabled. Turboism will not activate from the Start menu, desktop, or existing Cubism shortcuts. Choose No to go back, or Yes to deliberately continue."
LangString NoLaunchWarning ${LANG_SIMPCHINESE} "所有常规启动路径均已关闭。Turboism 不会通过开始菜单、桌面或现有 Cubism 快捷方式激活。选择“否”返回修改，或选择“是”明确继续。"
LangString NoLaunchWarning ${LANG_JAPANESE} "通常の起動経路がすべて無効です。Turboism はスタートメニュー、デスクトップ、または既存の Cubism ショートカットから有効になりません。「いいえ」で戻るか、「はい」で意図的に続行してください。"
LangString NoLaunchWarning ${LANG_KOREAN} "모든 일반 시작 경로가 해제되었습니다. Turboism은 시작 메뉴, 바탕 화면 또는 기존 Cubism 바로 가기를 통해 활성화되지 않습니다. [아니요]를 선택하여 돌아가거나 [예]를 선택하여 의도적으로 계속하세요."
LangString InitialConfigurationError ${LANG_ENGLISH} "Initial Cubism discovery or shortcut configuration failed. Turboism is installed; run Turboism_Configurator from the Start menu to retry."
LangString InitialConfigurationError ${LANG_SIMPCHINESE} "Cubism 初始扫描或快捷方式配置失败。Turboism 已完成安装；请从开始菜单运行 Turboism_Configurator 重试。"
LangString InitialConfigurationError ${LANG_JAPANESE} "Cubism の初期検出またはショートカット設定に失敗しました。Turboism のインストールは完了しています。スタートメニューから Turboism_Configurator を実行して再試行してください。"
LangString InitialConfigurationError ${LANG_KOREAN} "Cubism 초기 검색 또는 바로 가기 구성에 실패했습니다. Turboism은 설치되었습니다. 시작 메뉴에서 Turboism_Configurator를 실행하여 다시 시도하세요."
LangString BatIntegrationError ${LANG_ENGLISH} "Cubism BAT integration did not complete or elevation was canceled. Review the installer log and run Turboism_Configurator to inspect or retry; hash-protected files are never overwritten after an unknown edit."
LangString BatIntegrationError ${LANG_SIMPCHINESE} "Cubism BAT 集成未完成，或管理员授权已取消。请查看安装日志，并运行 Turboism_Configurator 检查或重试；检测到未知改动后绝不会覆盖受哈希保护的文件。"
LangString BatIntegrationError ${LANG_JAPANESE} "Cubism BAT の統合が完了しなかったか、管理者承認がキャンセルされました。インストーラーログを確認し、Turboism_Configurator で状態確認または再試行してください。不明な編集を検出したファイルは上書きしません。"
LangString BatIntegrationError ${LANG_KOREAN} "Cubism BAT 통합이 완료되지 않았거나 관리자 승인이 취소되었습니다. 설치 로그를 확인하고 Turboism_Configurator를 실행하여 점검하거나 다시 시도하세요. 알 수 없는 변경이 감지된 해시 보호 파일은 절대 덮어쓰지 않습니다."
LangString StartMenuResultCreated ${LANG_ENGLISH} "Turboism Start-menu shortcuts are enabled."
LangString StartMenuResultCreated ${LANG_SIMPCHINESE} "已启用 Turboism 开始菜单快捷方式。"
LangString StartMenuResultCreated ${LANG_JAPANESE} "Turboism のスタートメニューショートカットを有効にしました。"
LangString StartMenuResultCreated ${LANG_KOREAN} "Turboism 시작 메뉴 바로 가기가 활성화되었습니다."
LangString StartMenuResultSkipped ${LANG_ENGLISH} "Turboism Start-menu shortcuts are disabled; existing managed shortcuts were removed."
LangString StartMenuResultSkipped ${LANG_SIMPCHINESE} "已关闭 Turboism 开始菜单快捷方式，并移除现有托管快捷方式。"
LangString StartMenuResultSkipped ${LANG_JAPANESE} "Turboism のスタートメニューショートカットを無効にし、既存の管理対象ショートカットを削除しました。"
LangString StartMenuResultSkipped ${LANG_KOREAN} "Turboism 시작 메뉴 바로 가기를 비활성화하고 기존 관리형 바로 가기를 제거했습니다."
LangString BatResultApplied ${LANG_ENGLISH} "Official Cubism BAT integration was applied or already current. Originals are backed up."
LangString BatResultApplied ${LANG_SIMPCHINESE} "已应用 Cubism 官方 BAT 集成，或其已是当前形式；原文件已备份。"
LangString BatResultApplied ${LANG_JAPANESE} "Cubism 公式 BAT の統合を適用しました（既に最新の場合は変更なし）。元ファイルはバックアップ済みです。"
LangString BatResultApplied ${LANG_KOREAN} "Cubism 공식 BAT 통합을 적용했거나 이미 최신 상태입니다. 원본은 백업되어 있습니다."
LangString BatResultRestored ${LANG_ENGLISH} "Official Cubism BAT integration is disabled; owned unchanged BAT files were restored."
LangString BatResultRestored ${LANG_SIMPCHINESE} "已关闭 Cubism 官方 BAT 集成，并恢复仍由 Turboism 管理且未被改动的 BAT。"
LangString BatResultRestored ${LANG_JAPANESE} "Cubism 公式 BAT の統合を無効にし、Turboism 管理下で未変更の BAT を復元しました。"
LangString BatResultRestored ${LANG_KOREAN} "Cubism 공식 BAT 통합을 비활성화하고 Turboism이 관리하며 변경되지 않은 BAT를 복원했습니다."

LangString StartMenuConfigName ${LANG_ENGLISH} "Turboism_Configurator"
LangString StartMenuConfigName ${LANG_SIMPCHINESE} "Turboism_Configurator"
LangString StartMenuConfigName ${LANG_JAPANESE} "Turboism_Configurator"
LangString StartMenuConfigName ${LANG_KOREAN} "Turboism_Configurator"

LangString StartMenuUninstallName ${LANG_ENGLISH} "Turboism_Uninstall"
LangString StartMenuUninstallName ${LANG_SIMPCHINESE} "Turboism_Uninstall"
LangString StartMenuUninstallName ${LANG_JAPANESE} "Turboism_Uninstall"
LangString StartMenuUninstallName ${LANG_KOREAN} "Turboism_Uninstall"

LangString StartMenuLaunchName ${LANG_ENGLISH} "Turboism_Launch_Cubism"
LangString StartMenuLaunchName ${LANG_SIMPCHINESE} "Turboism_Launch_Cubism"
LangString StartMenuLaunchName ${LANG_JAPANESE} "Turboism_Launch_Cubism"
LangString StartMenuLaunchName ${LANG_KOREAN} "Turboism_Launch_Cubism"

LangString ConfigMigrationError ${LANG_ENGLISH} "config.json could not be validated or updated safely. No Turboism payload was changed."
LangString ConfigMigrationError ${LANG_SIMPCHINESE} "无法安全校验或更新 config.json；Turboism 载荷未发生变化。"
LangString ConfigMigrationError ${LANG_JAPANESE} "config.json を安全に検証または更新できませんでした。Turboism ペイロードは変更されていません。"
LangString ConfigMigrationError ${LANG_KOREAN} "config.json을 안전하게 검증하거나 업데이트할 수 없습니다. Turboism 페이로드는 변경되지 않았습니다."
LangString PluginRetireError ${LANG_ENGLISH} "Cannot retire an obsolete Turboism plugin JAR. Close Cubism and retry the upgrade."
LangString PluginRetireError ${LANG_SIMPCHINESE} "无法移除旧版 Turboism 插件 JAR。请关闭 Cubism 后重试升级。"
LangString PluginRetireError ${LANG_JAPANESE} "旧版 Turboism プラグイン JAR を削除できません。Cubism を終了してアップグレードを再試行してください。"
LangString PluginRetireError ${LANG_KOREAN} "더 이상 사용되지 않는 Turboism 플러그인 JAR을 제거할 수 없습니다. Cubism을 종료한 후 업그레이드를 다시 시도하세요."
LangString PayloadInstallError ${LANG_ENGLISH} "Cannot install the Turboism payload. Close Cubism and retry the installation."
LangString PayloadInstallError ${LANG_SIMPCHINESE} "无法安装 Turboism 载荷。请关闭 Cubism 后重试安装。"
LangString PayloadInstallError ${LANG_JAPANESE} "Turboism ペイロードをインストールできません。Cubism を終了してインストールを再試行してください。"
LangString PayloadInstallError ${LANG_KOREAN} "Turboism 페이로드를 설치할 수 없습니다. Cubism을 종료한 후 설치를 다시 시도하세요."
LangString ShortcutCleanupFailure ${LANG_ENGLISH} "Turboism shortcut restoration/cleanup failed. Nothing else was removed; retry uninstall after resolving the conflict."
LangString ShortcutCleanupFailure ${LANG_SIMPCHINESE} "Turboism 快捷方式恢复/清理失败。未删除其他内容；解决冲突后请重试卸载。"
LangString ShortcutCleanupFailure ${LANG_JAPANESE} "Turboism のショートカットの復元/クリーンアップに失敗しました。他の項目は削除していません。競合を解決してからアンインストールを再試行してください。"
LangString ShortcutCleanupFailure ${LANG_KOREAN} "Turboism 바로 가기 복원/정리에 실패했습니다. 다른 항목은 삭제되지 않았습니다. 충돌을 해결한 후 제거를 다시 시도하세요."

; 0.43.0 只按 en/zh/ja 三种界面语言生成了含空格的名称（韩语是后来加入的，
; 不存在该历史名称）。升级和卸载都必须删除这些 Turboism-owned 旧名称，避免
; 别名清单在两个路径间漂移。
!macro RemoveLegacyStartMenuShortcuts
  Delete "$SMPROGRAMS\Turboism\Turboism Configurator.lnk"
  Delete "$SMPROGRAMS\Turboism\Uninstall Turboism.lnk"
  Delete "$SMPROGRAMS\Turboism\Launch Cubism.lnk"
  Delete "$SMPROGRAMS\Turboism\Turboism 配置器.lnk"
  Delete "$SMPROGRAMS\Turboism\卸载 Turboism.lnk"
  Delete "$SMPROGRAMS\Turboism\启动 Cubism.lnk"
  Delete "$SMPROGRAMS\Turboism\Turboism 設定.lnk"
  Delete "$SMPROGRAMS\Turboism\Turboism をアンインストール.lnk"
  Delete "$SMPROGRAMS\Turboism\Cubism を起動.lnk"
!macroend

; ---------- 变量 ----------
Var EulaAcknowledgementsDialog
Var EulaAck1Checkbox
Var EulaAck2Checkbox
Var EulaAck3Checkbox
Var EulaAck4Checkbox
Var EulaAck1State
Var EulaAck2State
Var EulaAck3State
Var EulaAck4State
Var Mode                 ; 0 = Lite, 1 = Full（默认 Full）
Var ModeDialog
Var LiteRadio
Var FullRadio
Var installManagedGraal  ; 1 = 用户选择下载并安装托管 GraalVM（默认 0）
Var GraalDialog
Var GraalNowRadio
Var GraalLaterRadio
Var managedGraalHelp
Var CubismDiscoveryDialog
Var CubismDiscoveryList
Var CubismDiscoveryStatus
Var CubismDiscoveryNext
Var CubismDiscoveryWorkDir
Var CubismDiscoveryResult
Var CubismDiscoveryHandle
Var CubismDiscoveryGeneration
Var CubismDiscoveryStarted
Var CubismDiscoveryPollCount
Var CubismDiscoveryComplete
Var CubismDiscoverySupported
Var CubismDiscoveryOther
Var CubismDiscoveryResultSeen
Var CubismDiscoveryEndSeen
Var CubismDiscoveryMalformed
Var LaunchOptionsDialog
Var createStartMenu
Var createDesktopShortcut
Var integrateCubismBat
Var StartMenuCheckbox
Var DesktopShortcutCheckbox
Var IntegrateBatCheckbox
Var uncheckedPluginIds   ; 本次未勾选的插件 id，';' 分隔（Full 模式）
Var bundledPluginIds     ; 当前发布包内的全部插件 id，';' 分隔
Var pos
Var ch
Var len
Var line
Var next
Var unKeepConfig        ; 卸载时是否保留 config.json（1 = 保留，默认勾选）
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
  ; 语言选择：MUI 语言对话框在欢迎页之前显示。$LANGUAGE 在 .onInit 之前已由
  ; NSIS 按系统区域匹配（无匹配时为首个语言 English），因此对话框默认预选系统
  ; 语言，用户可显式切换；MUI_LANGDLL_ALLLANGUAGES 保证 CJK/韩语在任何系统
  ; codepage 下都可选。未定义 MUI_LANGDLL_REGISTRY_*，故不写注册表：
  ; 每次安装都重新确认语言。静默安装（/S）自动跳过。此处选择只影响安装界面，
  ; 不写入 config.json 的运行时 locale（后者仍由用户/宿主决定）。
  !insertmacro MUI_LANGDLL_DISPLAY
  StrCpy $EulaAck1State 0
  StrCpy $EulaAck2State 0
  StrCpy $EulaAck3State 0
  StrCpy $EulaAck4State 0
  StrCpy $Mode 1
  StrCpy $installManagedGraal 0
  StrCpy $CubismDiscoveryGeneration 0
  StrCpy $CubismDiscoveryStarted 0
  StrCpy $CubismDiscoveryPollCount 0
  StrCpy $CubismDiscoveryComplete 0
  StrCpy $createStartMenu 1
  StrCpy $createDesktopShortcut 1
  StrCpy $integrateCubismBat 0
  StrCpy $INSTDIR "$LOCALAPPDATA\Turboism"
FunctionEnd

Function LaunchTurboism
  ; Finish 页回调运行在安装器 UI 线程。避免 ShellExecute 在关闭窗口时同步完成
  ; 文件关联/安全扫描；Exec 只创建 cmd 进程即返回，由 cmd 异步运行启动脚本。
  Exec '"$SYSDIR\cmd.exe" /D /S /C ""$INSTDIR\launch-cubism-turboism.bat""'
FunctionEnd

Function OpenInstallDirectory
  Exec '"$WINDIR\explorer.exe" "$INSTDIR"'
FunctionEnd

; ---------- 最终用户运行声明：完整正文页后，单独进行四项确认 ----------
Function EulaAcknowledgementsCreate
  nsDialogs::Create 1018
  Pop $EulaAcknowledgementsDialog
  ${If} $EulaAcknowledgementsDialog == error
    Abort
  ${EndIf}
  !insertmacro MUI_HEADER_TEXT "$(EulaAcknowledgementsTitle)" "$(EulaAcknowledgementsSubtitle)"

  ; Keep the multiline rows compact and contiguous at the installer's 12pt font.
  ; Heights still cover the longest translated acknowledgement without large gaps.
  ${NSD_CreateCheckbox} 0 0 100% 24u "$(EulaAck1)"
  Pop $EulaAck1Checkbox
  ${NSD_CreateCheckbox} 0 24u 100% 30u "$(EulaAck2)"
  Pop $EulaAck2Checkbox
  ${NSD_CreateCheckbox} 0 54u 100% 38u "$(EulaAck3)"
  Pop $EulaAck3Checkbox
  ${NSD_CreateCheckbox} 0 92u 100% 30u "$(EulaAck4)"
  Pop $EulaAck4Checkbox

  ${NSD_SetState} $EulaAck1Checkbox $EulaAck1State
  ${NSD_SetState} $EulaAck2Checkbox $EulaAck2State
  ${NSD_SetState} $EulaAck3Checkbox $EulaAck3State
  ${NSD_SetState} $EulaAck4Checkbox $EulaAck4State
  ${NSD_OnBack} EulaAcknowledgementsSave
  nsDialogs::Show
FunctionEnd

Function EulaAcknowledgementsSave
  Pop $0
  ${NSD_GetState} $EulaAck1Checkbox $EulaAck1State
  ${NSD_GetState} $EulaAck2Checkbox $EulaAck2State
  ${NSD_GetState} $EulaAck3Checkbox $EulaAck3State
  ${NSD_GetState} $EulaAck4Checkbox $EulaAck4State
FunctionEnd

Function EulaAcknowledgementsLeave
  ${NSD_GetState} $EulaAck1Checkbox $EulaAck1State
  ${NSD_GetState} $EulaAck2Checkbox $EulaAck2State
  ${NSD_GetState} $EulaAck3Checkbox $EulaAck3State
  ${NSD_GetState} $EulaAck4Checkbox $EulaAck4State
  ${If} $EulaAck1State != ${BST_CHECKED}
  ${OrIf} $EulaAck2State != ${BST_CHECKED}
  ${OrIf} $EulaAck3State != ${BST_CHECKED}
  ${OrIf} $EulaAck4State != ${BST_CHECKED}
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(EulaRequired)"
    Abort
  ${EndIf}
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

Function GraalCreate
  nsDialogs::Create 1018
  Pop $GraalDialog
  ${If} $GraalDialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "$(GraalPageTitle)"
  Pop $0
  ${NSD_CreateLabel} 0 24u 100% 42u "$(GraalPageDescription)"
  Pop $managedGraalHelp
  ${NSD_CreateRadioButton} 0 67u 100% 16u "$(GraalNowChoice)"
  Pop $GraalNowRadio
  ${NSD_CreateRadioButton} 0 84u 100% 16u "$(GraalLaterChoice)"
  Pop $GraalLaterRadio
  ${NSD_CreateLabel} 12u 101u 96% 42u "$(GraalProgressHint)"
  Pop $0
  ${If} $installManagedGraal == 1
    ${NSD_Check} $GraalNowRadio
  ${Else}
    ${NSD_Check} $GraalLaterRadio
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function GraalLeave
  ${NSD_GetState} $GraalNowRadio $0
  ${If} $0 == 1
    StrCpy $installManagedGraal 1
  ${Else}
    StrCpy $installManagedGraal 0
  ${EndIf}
FunctionEnd

Function TrimCubismDiscoveryLine
  ${Do}
    StrLen $len $line
    ${If} $len == 0
      ${ExitDo}
    ${EndIf}
    IntOp $next $len - 1
    StrCpy $ch "$line" 1 $next
    ${If} $ch == "$\r"
    ${OrIf} $ch == "$\n"
      StrCpy $line "$line" $next
    ${Else}
      ${ExitDo}
    ${EndIf}
  ${Loop}
FunctionEnd

Function SplitPipeFirst
  StrCpy $1 ""
  StrCpy $pos 0
  StrLen $len $0
  ${Do}
    ${If} $pos >= $len
      ${ExitDo}
    ${EndIf}
    StrCpy $ch "$0" 1 $pos
    ${If} $ch == "|"
      IntOp $next $pos + 1
      StrCpy $1 "$0" "" $next
      StrCpy $0 "$0" $pos
      ${ExitDo}
    ${EndIf}
    IntOp $pos $pos + 1
  ${Loop}
FunctionEnd

Function CubismDiscoveryEnableNext
  StrCpy $CubismDiscoveryComplete 1
  EnableWindow $CubismDiscoveryNext 1
FunctionEnd

Function CubismDiscoveryFail
  ${NSD_KillTimer} CubismDiscoveryPoll
  ${NSD_SetText} $CubismDiscoveryStatus "$(CubismDiscoveryFailed)"
  Call CubismDiscoveryEnableNext
FunctionEnd

Function CubismDiscoveryPoll
  IntOp $CubismDiscoveryPollCount $CubismDiscoveryPollCount + 1
  IfFileExists "$CubismDiscoveryResult" ReadCubismDiscoveryResult
  ${If} $CubismDiscoveryPollCount >= 480
    ${NSD_KillTimer} CubismDiscoveryPoll
    ${NSD_SetText} $CubismDiscoveryStatus "$(CubismDiscoveryTimeout)"
    Call CubismDiscoveryEnableNext
  ${EndIf}
  Return

ReadCubismDiscoveryResult:
  ${NSD_KillTimer} CubismDiscoveryPoll
  StrCpy $CubismDiscoveryResultSeen 0
  StrCpy $CubismDiscoveryEndSeen 0
  StrCpy $CubismDiscoveryMalformed 0
  StrCpy $CubismDiscoverySupported 0
  StrCpy $CubismDiscoveryOther 0
  StrCpy $2 0
  FileOpen $CubismDiscoveryHandle "$CubismDiscoveryResult" r
  ${If} $CubismDiscoveryHandle == ""
    Call CubismDiscoveryFail
    Return
  ${EndIf}

CubismDiscoveryReadLoop:
  ClearErrors
  FileReadUTF16LE $CubismDiscoveryHandle $line
  ${If} ${Errors}
    Goto CubismDiscoveryReadDone
  ${EndIf}
  Call TrimCubismDiscoveryLine
  ${If} $line == ""
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  ${If} $CubismDiscoveryEndSeen == 1
    StrCpy $CubismDiscoveryMalformed 1
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  ${If} $2 == 0
    ${If} $line != "TURBOISM_CUBISM_SCAN_V1"
      StrCpy $CubismDiscoveryMalformed 1
    ${EndIf}
    StrCpy $2 1
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  ${If} $line == "END"
    ${If} $CubismDiscoveryResultSeen != 1
      StrCpy $CubismDiscoveryMalformed 1
    ${EndIf}
    StrCpy $CubismDiscoveryEndSeen 1
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  StrCpy $3 "$line" 7
  ${If} $3 == "RESULT|"
    ${If} $CubismDiscoveryResultSeen != 0
      StrCpy $CubismDiscoveryMalformed 1
      Goto CubismDiscoveryReadLoop
    ${EndIf}
    StrCpy $0 "$line" "" 7
    Call SplitPipeFirst
    StrCpy $4 "$0"
    StrCpy $0 "$1"
    Call SplitPipeFirst
    StrCpy $CubismDiscoverySupported "$0"
    StrCpy $0 "$1"
    Call SplitPipeFirst
    StrCpy $CubismDiscoveryOther "$0"
    ${If} $4 == "OK"
      ${If} $1 != ""
        StrCpy $CubismDiscoveryMalformed 1
      ${EndIf}
    ${ElseIf} $4 == "ERROR"
      StrCpy $CubismDiscoveryMalformed 1
    ${Else}
      StrCpy $CubismDiscoveryMalformed 1
    ${EndIf}
    StrCpy $CubismDiscoveryResultSeen 1
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  StrCpy $3 "$line" 8
  ${If} $3 == "DISPLAY|"
    ${If} $CubismDiscoveryResultSeen != 1
      StrCpy $CubismDiscoveryMalformed 1
      Goto CubismDiscoveryReadLoop
    ${EndIf}
    StrCpy $3 "$line" "" 8
    SendMessage $CubismDiscoveryList ${LB_ADDSTRING} 0 "STR:$3"
    SendMessage $CubismDiscoveryList ${LB_SETHORIZONTALEXTENT} 8192 0
    Goto CubismDiscoveryReadLoop
  ${EndIf}
  StrCpy $CubismDiscoveryMalformed 1
  Goto CubismDiscoveryReadLoop

CubismDiscoveryReadDone:
  FileClose $CubismDiscoveryHandle
  ${If} $CubismDiscoveryEndSeen != 1
  ${OrIf} $CubismDiscoveryResultSeen != 1
  ${OrIf} $CubismDiscoveryMalformed != 0
    Call CubismDiscoveryFail
    Return
  ${EndIf}
  ${If} $CubismDiscoverySupported == 0
    ${NSD_SetText} $CubismDiscoveryStatus "$(CubismDiscoveryNone)"
  ${Else}
    ${NSD_SetText} $CubismDiscoveryStatus "$(CubismDiscoveryComplete)"
  ${EndIf}
  Call CubismDiscoveryEnableNext
FunctionEnd

Function CubismDiscoveryBack
  Pop $0
  ${NSD_KillTimer} CubismDiscoveryPoll
  EnableWindow $CubismDiscoveryNext 1
  StrCpy $CubismDiscoveryComplete 0
FunctionEnd

Function CubismDiscoveryCreate
  nsDialogs::Create 1018
  Pop $CubismDiscoveryDialog
  ${If} $CubismDiscoveryDialog == error
    Abort
  ${EndIf}
  !insertmacro MUI_HEADER_TEXT "$(CubismDiscoveryTitle)" ""
  ${NSD_CreateLabel} 0 0 100% 28u "$(CubismDiscoveryScanning)"
  Pop $CubismDiscoveryStatus
  ${NSD_CreateListBox} 0 34u 100% 112u ""
  Pop $CubismDiscoveryList
  ${NSD_AddStyle} $CubismDiscoveryList ${WS_HSCROLL}
  ${NSD_OnBack} CubismDiscoveryBack

  GetDlgItem $CubismDiscoveryNext $HWNDPARENT 1
  EnableWindow $CubismDiscoveryNext 0
  StrCpy $CubismDiscoveryComplete 0
  StrCpy $CubismDiscoveryPollCount 0
  ${If} $CubismDiscoveryStarted == 1
    Call CubismDiscoveryPoll
    ${If} $CubismDiscoveryComplete != 1
      ${NSD_CreateTimer} CubismDiscoveryPoll 250
    ${EndIf}
  ${Else}
    IntOp $CubismDiscoveryGeneration $CubismDiscoveryGeneration + 1
    InitPluginsDir
    StrCpy $CubismDiscoveryWorkDir "$PLUGINSDIR\Turboism-discovery-$CubismDiscoveryGeneration"
    StrCpy $CubismDiscoveryResult "$CubismDiscoveryWorkDir\cubism-scan.result"
    SetOutPath "$CubismDiscoveryWorkDir"
    File /oname=turboism-agent.jar "${STAGING_DIR}/turboism-agent.jar"
    File /oname=configure_turboism.ps1 "${STAGING_DIR}/configure_turboism.ps1"
    File /oname=cubism-launch-common.ps1 "${STAGING_DIR}/cubism-launch-common.ps1"
    ClearErrors
    ; ShellExecute keeps discovery asynchronous; SW_HIDE prevents its console
    ; process from taking focus away from the installer wizard.
    ExecShell "" "$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$CubismDiscoveryWorkDir\configure_turboism.ps1" -Home "$CubismDiscoveryWorkDir" -InstallerDiscoveryOutput "$CubismDiscoveryResult"' SW_HIDE
    ${If} ${Errors}
      Call CubismDiscoveryFail
    ${Else}
      StrCpy $CubismDiscoveryStarted 1
      ${NSD_CreateTimer} CubismDiscoveryPoll 250
    ${EndIf}
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function CubismDiscoveryLeave
  ${If} $CubismDiscoveryComplete != 1
    Abort
  ${EndIf}
  ${NSD_KillTimer} CubismDiscoveryPoll
FunctionEnd

Function LaunchOptionsCreate
  nsDialogs::Create 1018
  Pop $LaunchOptionsDialog
  ${If} $LaunchOptionsDialog == error
    Abort
  ${EndIf}
  ${NSD_CreateLabel} 0 0 100% 24u "$(LaunchOptionsTitle)"
  Pop $0
  ${NSD_CreateCheckbox} 0 30u 100% 18u "$(StartMenuOption)"
  Pop $StartMenuCheckbox
  ${NSD_CreateCheckbox} 0 50u 100% 18u "$(DesktopShortcutOption)"
  Pop $DesktopShortcutCheckbox
  ${NSD_CreateCheckbox} 0 70u 100% 32u "$(BatIntegrationOption)"
  Pop $IntegrateBatCheckbox
  ${If} $createStartMenu == 1
    ${NSD_Check} $StartMenuCheckbox
  ${EndIf}
  ${If} $createDesktopShortcut == 1
    ${NSD_Check} $DesktopShortcutCheckbox
  ${EndIf}
  ${If} $integrateCubismBat == 1
    ${NSD_Check} $IntegrateBatCheckbox
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function LaunchOptionsLeave
  ${NSD_GetState} $StartMenuCheckbox $createStartMenu
  ${NSD_GetState} $DesktopShortcutCheckbox $createDesktopShortcut
  ${NSD_GetState} $IntegrateBatCheckbox $integrateCubismBat
  ${If} $createStartMenu == 0
  ${AndIf} $createDesktopShortcut == 0
  ${AndIf} $integrateCubismBat == 0
    MessageBox MB_ICONEXCLAMATION|MB_YESNO|MB_DEFBUTTON2 "$(NoLaunchWarning)" IDYES ContinueWithoutLaunch
    Abort
  ContinueWithoutLaunch:
  ${EndIf}
FunctionEnd

Function .onInstSuccess
  ; 预安装扫描目录包含 agent JAR。若留到 Finish 按钮关闭安装器时由 NSIS
  ; 自动清理，会表现为点击“完成”后的短暂卡顿；在进入 Finish 页前主动释放。
  ${If} $CubismDiscoveryWorkDir != ""
    RMDir /r "$CubismDiscoveryWorkDir"
    StrCpy $CubismDiscoveryWorkDir ""
    StrCpy $CubismDiscoveryResult ""
  ${EndIf}
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -InitializeSelection'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(InitialConfigurationError)"
    Return
  ${EndIf}
  ${If} $createStartMenu == 1
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -EnableShortcuts'
    Pop $0
  ${Else}
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -DisableShortcuts'
    Pop $0
  ${EndIf}
  ${If} $0 != 0
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(InitialConfigurationError)"
    Return
  ${EndIf}
  ${If} $integrateCubismBat == 1
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -IntegrateBat'
    Pop $0
  ${Else}
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -DisableBat'
    Pop $0
  ${EndIf}
  ${If} $0 != 0
    MessageBox MB_ICONEXCLAMATION|MB_OK "$(BatIntegrationError)"
  ${EndIf}
FunctionEnd

; ---------- Section 声明 ----------
; 配置必须在任何永久载荷、托管运行时或插件 JAR 写入前完成。这里只将当前
; helper 解压到 NSIS 私有临时目录；无效/未来配置会在安装树变化前失败关闭。
Section "-写入配置" SecConfig
  CreateDirectory "$INSTDIR"
  StrCpy $uncheckedPluginIds ""
  StrCpy $bundledPluginIds ""
  Call CollectUncheckedPluginIds
  Call SetBundledPluginIds
  SetOutPath "$PLUGINSDIR\Turboism-config"
  File "/oname=configure_turboism.ps1" "${STAGING_DIR}/configure_turboism.ps1"
  File "/oname=cubism-launch-common.ps1" "${STAGING_DIR}/cubism-launch-common.ps1"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Turboism-config\configure_turboism.ps1" -Home "$INSTDIR" -ApplyInstallerSelection -BundledPluginIds "$bundledPluginIds" -DisabledPluginIds "$uncheckedPluginIds"'
  Pop $0
  RMDir /r "$PLUGINSDIR\Turboism-config"
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(ConfigMigrationError)"
    Abort
  ${EndIf}
SectionEnd

; 插件 Section 由 plugin-sections.nsh 提供（见文件头注释）
Section "-核心文件" SecCore
  SetOverwrite on
  ; Managed-upgrade retirement: run the CURRENT staged helper from NSIS's
  ; temporary directory, never an older installed configurator that may not
  ; support -RetirePlugins. The helper deletes only regular JARs whose embedded
  ; plugin.json id is retired; filename alone never authorizes deletion.
  SetOutPath "$PLUGINSDIR\Turboism-retire"
  File "/oname=configure_turboism.ps1" "${STAGING_DIR}/configure_turboism.ps1"
  File "/oname=cubism-launch-common.ps1" "${STAGING_DIR}/cubism-launch-common.ps1"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Turboism-retire\configure_turboism.ps1" -Home "$INSTDIR" -RetirePlugins'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PluginRetireError)"
    Abort
  ${EndIf}
  RMDir /r "$PLUGINSDIR\Turboism-retire"

  ; 清单和校验 helper 是很小的临时引导载荷。永久核心文件先逐项检查目标 SHA-256，
  ; 仅将缺失或不同的条目解压到私有临时目录，再按同一清单校验源、复制并复核目标。
  SetOutPath "$PLUGINSDIR\Turboism-payload-bootstrap"
  File "/oname=install-jar-payload.ps1" "${STAGING_DIR}/install-jar-payload.ps1"
  SetOutPath "$PLUGINSDIR\Turboism-payload-manifests"
  File "${GENERATED_DIR}/payload-core.sha256"
  File "${GENERATED_DIR}/payload-plugins.sha256"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Turboism-payload-bootstrap\install-jar-payload.ps1" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-core.sha256" -DestinationRoot "$INSTDIR" -PlanRoot "$PLUGINSDIR\Turboism-core-plan" -PlanOnly'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  Call ExtractCorePayload
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\Turboism-payload-bootstrap\install-jar-payload.ps1" -SourceRoot "$PLUGINSDIR\Turboism-core-payload" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-core.sha256" -DestinationRoot "$INSTDIR"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  RMDir /r "$PLUGINSDIR\Turboism-core-payload"
  RMDir /r "$PLUGINSDIR\Turboism-core-plan"
  RMDir /r "$PLUGINSDIR\Turboism-payload-bootstrap"
  Delete "$PLUGINSDIR\Turboism-payload-manifests\payload-core.sha256"
SectionEnd

Section "-托管 GraalVM" SecManagedGraal
  ${If} $installManagedGraal == 1
    DetailPrint "$(ManagedGraalStarting)"
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\install-managed-graal.ps1" -Home "$INSTDIR" -Gui'
    Pop $0
    ${If} $0 != 0
      DetailPrint "$(ManagedGraalInstallError)"
      MessageBox MB_ICONEXCLAMATION|MB_OK "$(ManagedGraalInstallError)"
    ${EndIf}
  ${EndIf}
SectionEnd

; 插件 Section + 描述 + 选择状态函数（由 assemble-release.sh 生成，勿手改）
!include "plugin-sections.nsh"

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
  !insertmacro RemoveLegacyStartMenuShortcuts
  ${If} $createStartMenu == 1
    CreateDirectory "$SMPROGRAMS\Turboism"
    CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuConfigName).lnk" "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" "-NoProfile -ExecutionPolicy Bypass -File $\"$INSTDIR\configure_turboism.ps1$\"" "$INSTDIR\turboism.ico" 0
    CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuUninstallName).lnk" "$INSTDIR\uninstall.exe" "" "$INSTDIR\turboism.ico" 0
    CreateShortCut "$SMPROGRAMS\Turboism\$(StartMenuLaunchName).lnk" "$INSTDIR\launch-cubism-turboism.bat" "" "$INSTDIR\turboism.ico" 0 SW_SHOWNORMAL "" "$INSTDIR"
  ${Else}
    Delete "$SMPROGRAMS\Turboism\$(StartMenuConfigName).lnk"
    Delete "$SMPROGRAMS\Turboism\$(StartMenuUninstallName).lnk"
    Delete "$SMPROGRAMS\Turboism\$(StartMenuLaunchName).lnk"
    RMDir "$SMPROGRAMS\Turboism"
  ${EndIf}
  Delete "$DESKTOP\Turboism_Launch_Cubism.lnk"
  ${If} $createDesktopShortcut == 1
    CreateShortCut "$DESKTOP\Turboism_Launch_Cubism.lnk" "$INSTDIR\launch-cubism-turboism.bat" "" "$INSTDIR\turboism.ico" 0 SW_SHOWNORMAL "" "$INSTDIR"
  ${EndIf}
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
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\configure_turboism.ps1" -Home "$INSTDIR" -Cleanup'
  Pop $0
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
  Delete "$DESKTOP\Turboism_Launch_Cubism.lnk"
  !insertmacro RemoveLegacyStartMenuShortcuts
  RMDir "$SMPROGRAMS\Turboism"
  ; 安装文件
  Delete "$INSTDIR\turboism-agent.jar"
  Delete "$INSTDIR\launch-cubism-turboism.bat"
  Delete "$INSTDIR\launch-cubism-turboism.ps1"
  Delete "$INSTDIR\cubism-launch-common.ps1"
  Delete "$INSTDIR\configure_turboism.ps1"
  Delete "$INSTDIR\install-jar-payload.ps1"
  Delete "$INSTDIR\install-managed-graal.ps1"
  Delete "$INSTDIR\turboism.ico"
  Delete "$INSTDIR\turboism.png"
  ; The configurator removes managed state only after validated shortcut cleanup.
  Delete "$INSTDIR\README*.txt"
  Delete "$INSTDIR\LICENSE"
  Delete "$INSTDIR\EULA.en.txt"
  Delete "$INSTDIR\EULA.zh-Hans.txt"
  Delete "$INSTDIR\EULA.ja.txt"
  Delete "$INSTDIR\uninstall.exe"
  ; 运行时数据目录
  RMDir /r "$INSTDIR\plugins"
  RMDir /r "$INSTDIR\graal"
  RMDir "$INSTDIR\runtimes"
  RMDir /r "$INSTDIR\logs"
  RMDir /r "$INSTDIR\state"
  RMDir /r "$INSTDIR\cache"
  ; config.json：按卸载确认页复选框决定（默认勾选 = 保留）
  ${If} $unKeepConfig == 0
    Delete "$INSTDIR\config.json"
  ${Else}
    ; 保留配置时目录必须继续存在，恢复 config/data 目录以便下次升级沿用。
    CreateDirectory "$INSTDIR\config"
    CreateDirectory "$INSTDIR\data"
  ${EndIf}
  ; 清理空目录（失败无害：文件被占用/非空时忽略）
  RMDir "$INSTDIR"
SectionEnd

; 卸载确认页 SHOW：创建「保留 config.json」复选框（默认勾选）。MUI 的确认页是
; $HWNDPARENT 下的子对话框；控件必须属于该页，否则鼠标输入会落到错误窗口并造成卡顿。
Function un.ConfirmShow
  StrCpy $unKeepConfig 1
  StrCpy $unCfgCheckbox 0
  IntOp $unCfgStyle ${WS_CHILD} | ${WS_VISIBLE}
  IntOp $unCfgStyle $unCfgStyle | ${WS_TABSTOP}
  IntOp $unCfgStyle $unCfgStyle | 0x0003        ; BS_AUTOCHECKBOX
  System::Call 'user32::CreateWindowEx(i 0, t "BUTTON", t "$(UnKeepConfigLabel)", i $unCfgStyle, i 24, i 96, i 330, i 28, i $mui.UnConfirmPage, i 2000, i 0, i 0) i .r$unCfgCheckbox'
  ${If} $unCfgCheckbox != 0
    SendMessage $unCfgCheckbox ${BM_SETCHECK} 1 0
  ${EndIf}
FunctionEnd

; 卸载确认页 LEAVE：读取复选框状态到 $unKeepConfig
Function un.ConfirmLeave
  ${If} $unCfgCheckbox != 0
    SendMessage $unCfgCheckbox ${BM_GETCHECK} 0 0 $unKeepConfig
  ${EndIf}
FunctionEnd
