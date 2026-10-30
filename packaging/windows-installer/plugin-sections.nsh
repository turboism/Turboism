; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。
; 标准安装器携带全部第一方插件 JAR。

Section "-插件载荷" SecPluginPayload
  Call WritePluginSelectionSkipMarkers
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR" -PlanRoot "$PLUGINSDIR\Turboism-plugin-plan" -PlanOnly'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0000.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=acp.jar" "${STAGING_DIR}/plugins/acp.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0001.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=artmesh-mirror.jar" "${STAGING_DIR}/plugins/artmesh-mirror.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0002.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=boundingbox-warp-mirror.jar" "${STAGING_DIR}/plugins/boundingbox-warp-mirror.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0003.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0004.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=command-palette.jar" "${STAGING_DIR}/plugins/command-palette.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0005.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0006.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=deformer-batch-rename.jar" "${STAGING_DIR}/plugins/deformer-batch-rename.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0007.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=external-psd-edit.jar" "${STAGING_DIR}/plugins/external-psd-edit.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0008.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=frame-animation-import.jar" "${STAGING_DIR}/plugins/frame-animation-import.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0009.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=geometric-mesh-layout.jar" "${STAGING_DIR}/plugins/geometric-mesh-layout.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0010.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=history-panel.jar" "${STAGING_DIR}/plugins/history-panel.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0011.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mcp.jar" "${STAGING_DIR}/plugins/mcp.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0012.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mesh-edit-mirror-axis-enhance.jar" "${STAGING_DIR}/plugins/mesh-edit-mirror-axis-enhance.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0013.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=model-import-3d.jar" "${STAGING_DIR}/plugins/model-import-3d.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0014.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0015.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=parameter-batch-transfer.jar" "${STAGING_DIR}/plugins/parameter-batch-transfer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0016.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=parameter-sync.jar" "${STAGING_DIR}/plugins/parameter-sync.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0017.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=perf-stats.jar" "${STAGING_DIR}/plugins/perf-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0018.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0019.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=protected-export.jar" "${STAGING_DIR}/plugins/protected-export.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0020.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=psd-clip-mask-import.jar" "${STAGING_DIR}/plugins/psd-clip-mask-import.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0021.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0022.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0023.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=selection-brush.jar" "${STAGING_DIR}/plugins/selection-brush.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0024.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0025.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-dalsoo-polygon.jar" "${STAGING_DIR}/plugins/atlas-dalsoo-polygon.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0026.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-folder-layout.jar" "${STAGING_DIR}/plugins/atlas-folder-layout.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0027.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-bitmask-nesting.jar" "${STAGING_DIR}/plugins/atlas-bitmask-nesting.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0028.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0029.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0030.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=warp-deformer-alt-symmetry.jar" "${STAGING_DIR}/plugins/warp-deformer-alt-symmetry.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0031.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=webdav-backup.jar" "${STAGING_DIR}/plugins/webdav-backup.jar"
  ${EndIf}
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -SourceRoot "$PLUGINSDIR\Turboism-plugin-payload" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR" -PlanRoot "$PLUGINSDIR\Turboism-plugin-plan"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  RMDir /r "$PLUGINSDIR\Turboism-plugin-payload"
  RMDir /r "$PLUGINSDIR\Turboism-plugin-plan"
  Delete "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256"
  RMDir "$PLUGINSDIR\Turboism-payload-manifests"
SectionEnd

Var PluginState_0
Var PluginState_1
Var PluginState_2
Var PluginState_3
Var PluginState_4
Var PluginState_5
Var PluginState_6
Var PluginState_7
Var PluginState_8
Var PluginState_9
Var PluginState_10
Var PluginState_11
Var PluginState_12
Var PluginState_13
Var PluginState_14
Var PluginState_15
Var PluginState_16
Var PluginState_17
Var PluginState_18
Var PluginState_19
Var PluginState_20
Var PluginState_21
Var PluginState_22
Var PluginState_23
Var PluginState_24
Var PluginState_25
Var PluginState_26
Var PluginState_27
Var PluginState_28
Var PluginState_29
Var PluginState_30
Var PluginState_31

LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_ENGLISH} "Turboism ACP 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_ENGLISH} "ACP v1 Agent and Settings windows connecting a user-installed agent to the authenticated Turboism MCP server."
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_SIMPCHINESE} "Turboism ACP 智能体 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_SIMPCHINESE} "将用户安装的 ACP v1 Agent 连接到已认证的 Turboism MCP 服务器的 Agent/设置窗口。"
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_JAPANESE} "Turboism ACP エージェント 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_JAPANESE} "ユーザーがインストールした ACP v1 エージェントを認証済み Turboism MCP サーバーに接続するエージェント/設定ウィンドウ。"
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_KOREAN} "Turboism ACP 에이전트 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_KOREAN} "사용자가 설치한 ACP v1 에이전트를 인증된 Turboism MCP 서버에 연결하는 에이전트/설정 창."
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_ENGLISH} "ArtMesh Mirror 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_ENGLISH} "Independent ArtMesh mirror editing with a freely movable and rotatable axis."
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_SIMPCHINESE} "图形网格镜像 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_SIMPCHINESE} "独立的图形网格镜像编辑，支持自由移动与旋转镜像轴。"
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_JAPANESE} "アートメッシュミラー 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_JAPANESE} "独立したアートメッシュのミラー編集。対称軸を移動・回転できます。"
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_KOREAN} "아트메시 미러 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_KOREAN} "축 이동과 회전을 지원하는 독립 아트메시 미러 편집입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_ENGLISH} "BoundingBox Warp Mirror 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_ENGLISH} "Adds a BoundingBox overlay button that opens a mirror-direction dialog and applies a one-shot whole-object Warp Deformer mirror, optionally preserving descendant geometry."
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_SIMPCHINESE} "包围框曲面镜像 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_SIMPCHINESE} "在包围框叠加层添加镜像按钮，打开镜像方向对话框并对所选曲面变形器执行单次整体镜像，可选保持子物体形状不变。"
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_JAPANESE} "バウンディングボックス ワープミラー 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_JAPANESE} "バウンディングボックスのオーバーレイにミラーボタンを追加し、方向選択ダイアログから選択中のワープデフォーマ全体を一括ミラーリングします（子オブジェクトの形状維持に対応）。"
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_KOREAN} "바운딩 박스 워프 미러 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_KOREAN} "바운딩 박스 오버레이에 미러 버튼을 추가하여 미러 방향 대화상자를 열고, 선택한 워프 디포머 전체에 일회성 미러를 적용합니다(자식 오브젝트 형태 유지 선택 가능)."
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Clip Mask Viewer 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Read-only clip-mask duplicate checker and visualizer: Turboism tab entry, graph/table inspector window, editor selection highlight, GUID copy."
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "剪裁蒙版查看器 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "Turboism 的剪裁蒙版查看器。"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "クリップマスクビューアー 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "Turboism のクリップマスクビューアー。"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_KOREAN} "클립 마스크 뷰어 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_KOREAN} "Turboism 클립 마스크 뷰어 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_ENGLISH} "Command Palette 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_ENGLISH} "Ctrl+K command launcher over the supported Editor commands."
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_SIMPCHINESE} "命令面板 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_SIMPCHINESE} "通过 Ctrl+K 启动受支持的 Editor 命令的启动器。"
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_JAPANESE} "コマンドパレット 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_JAPANESE} "対応する Editor コマンドを Ctrl+K で起動するランチャー。"
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_KOREAN} "커맨드 팔레트 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_KOREAN} "지원되는 Editor 명령을 Ctrl+K로 실행하는 런처입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Cubism Tab Filter 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette tabs."
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Cubism 标签筛选器 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Turboism 的Cubism 标签筛选器。"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Cubism タブフィルター 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Turboism のCubism タブフィルター。"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_KOREAN} "Cubism 탭 필터 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_KOREAN} "Turboism Cubism 탭 필터 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_ENGLISH} "Batch Rename Deformers 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_ENGLISH} "Rename deformers from child names and bound parameters."
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_SIMPCHINESE} "变形器批量重命名 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_SIMPCHINESE} "根据子物体名称和绑定参数批量重命名变形器。"
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_JAPANESE} "デフォーマを一括リネーム 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_JAPANESE} "子オブジェクト名と関連パラメータからデフォーマの名前を変更します。"
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_KOREAN} "디포머 이름 일괄 변경 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_KOREAN} "자식 이름과 연결된 파라미터로 디포머 이름을 일괄 변경합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_ENGLISH} "External PSD Edit Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_ENGLISH} "Edit the complete raw PSD of selected ArtMeshes in the configured or system default application; saves replace the raw image through the Cubism native importer."
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_SIMPCHINESE} "外部 PSD 编辑插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_SIMPCHINESE} "在自定义或系统默认程序中编辑所选 ArtMesh 的完整原图 PSD；保存后通过 Cubism 原生导入器替换原图。"
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_JAPANESE} "外部PSD編集プラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_JAPANESE} "選択したArtMeshの完全な原画PSDを設定したアプリまたはシステムの既定のアプリで編集します。保存するとCubismネイティブインポーターで原画が置き換えられます。"
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_KOREAN} "외부 PSD 편집 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_KOREAN} "선택한 ArtMesh의 전체 원본 이미지 PSD를 설정한 앱 또는 시스템 기본 앱에서 편집합니다. 저장하면 Cubism 네이티브 가져오기로 원본 이미지가 교체됩니다."
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_ENGLISH} "Frame Animation Import 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_ENGLISH} "Import sequence frame animations from sprite sheets or image lists and bind to parameters with keyframe visibility."
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_SIMPCHINESE} "帧动画导入 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_SIMPCHINESE} "从精灵表切片或多张图片导入序列帧动画，并自动生成绑定参数与关键点显示。"
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_JAPANESE} "コマアニメーションインポート 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_JAPANESE} "スプライトシートまたは連番画像からコマアニメーションをインポートし、パラメータのキーフォーム表示を自動設定します。"
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_KOREAN} "프레임 애니메이션 가져오기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_KOREAN} "스프라이트 시트 또는 연번 이미지에서 프레임 애니메이션을 가져와 파라미터 키폼 표시를 자동으로 구성합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_geometric_mesh_layout ${LANG_ENGLISH} "Geometric Mesh Layout 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_geometric_mesh_layout ${LANG_ENGLISH} "Generates circular or regular-polygon mesh vertices and edges around a canvas anchor in mesh edit mode."
LangString PLUGIN_NAME_dev_turboism_plugin_geometric_mesh_layout ${LANG_SIMPCHINESE} "几何网格布点 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_geometric_mesh_layout ${LANG_SIMPCHINESE} "在网格编辑模式下以画布锚点为中心生成圆形或正多边形网格点线。"
LangString PLUGIN_NAME_dev_turboism_plugin_geometric_mesh_layout ${LANG_JAPANESE} "幾何メッシュ配置 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_geometric_mesh_layout ${LANG_JAPANESE} "メッシュ編集モードでキャンバスのアンカーを中心に円形または正多角形の頂点とエッジを生成します。"
LangString PLUGIN_NAME_dev_turboism_plugin_geometric_mesh_layout ${LANG_KOREAN} "기하학 메시 배치 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_geometric_mesh_layout ${LANG_KOREAN} "메시 편집 모드에서 캔버스 앵커를 중심으로 원형 또는 정다각형 메시 정점과 엣지를 생성합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "History Panel Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "Photoshop-style history pane with native Undo history and snapshot-bound Undo/Redo navigation."
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "历史记录面板插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "Photoshop 风格历史面板，显示原生撤销历史并提供绑定快照的撤销/重做导航。"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "履歴パネルプラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "ネイティブ Undo 履歴とスナップショットに束縛された Undo/Redo ナビゲーションを提供する Photoshop 風履歴パネルです。"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_KOREAN} "기록 패널 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_KOREAN} "기본 실행 취소 기록과 스냅샷 기반 실행 취소/다시 실행 탐색을 제공하는 Photoshop 스타일 기록 패널입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Turboism MCP Server 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Loopback MCP server for typed Cubism inspection, editing, history, and Editor-command workflows."
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "Turboism MCP 服务器 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "通过类型化 MCP 工作流公开模型、参数、历史记录和编辑器命令的环回服务器。"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_JAPANESE} "Turboism MCP サーバー 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_JAPANESE} "モデル、パラメータ、履歴、エディターコマンドを型付き MCP ワークフローとして公開するループバックサーバー。"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_KOREAN} "Turboism MCP 서버 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_KOREAN} "모델, 파라미터, 기록 및 에디터 명령을 형식화된 MCP 워크플로로 제공하는 루프백 서버입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Mesh Inspector and Mirror-Axis Tools 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Inspects mesh/deformer state and contributes the bounded mirror-axis rotation control for verified Cubism hosts."
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "网格检查与镜像轴工具 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "Turboism 的网格检查与镜像轴工具。"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "メッシュ検査とミラー軸ツール 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "Turboism のメッシュ検査とミラー軸ツール。"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_KOREAN} "메시 검사 및 미러 축 도구 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_KOREAN} "Turboism 메시 검사 및 미러 축 도구 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_ENGLISH} "3D Model Import 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_ENGLISH} "Imports GLB, glTF, OBJ/MTL and ZIP models as native Cubism meshes with material textures, XYZ rotation, bundled shape presets and an interactive import preview."
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_SIMPCHINESE} "3D 模型导入 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_SIMPCHINESE} "将 GLB、glTF、OBJ/MTL 和 ZIP 模型导入为 Cubism 原生网格，支持材质贴图、XYZ 旋转、基础形状预设和导入时的交互式 3D 预览。"
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_JAPANESE} "3D モデルのインポート 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_JAPANESE} "GLB、glTF、OBJ/MTL、ZIP モデルを Cubism ネイティブメッシュとして読み込みます。材質テクスチャ、XYZ 回転、基本形状プリセット、読み込み時のインタラクティブな 3D プレビューに対応します。"
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_KOREAN} "3D 모델 가져오기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_KOREAN} "GLB, glTF, OBJ/MTL 및 ZIP 모델을 Cubism 네이티브 메시로 가져옵니다. 재질 텍스처, XYZ 회전, 기본 도형 프리셋과 가져오기 중 대화형 3D 미리 보기를 지원합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Palette Label Style Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Label text and background colors for Deformer, Part, and Parameter palette entries via context menus."
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "面板标签样式插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "通过右键菜单为变形器、部件和参数面板条目设置文本与背景颜色。"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "パレットラベルスタイルプラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "デフォーマ、パーツ、パラメータパレットの各項目にコンテキストメニューからテキスト色と背景色を設定します。"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_KOREAN} "팔레트 레이블 스타일 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_KOREAN} "Turboism 팔레트 레이블 스타일 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Parameter Batch Transfer 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Batch-transfer parameter bindings of one ArtMesh or Deformer to other parameters, with optional inversion, through a modal dialog."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "参数批量传输 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "Turboism 的参数批量传输。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "パラメータ一括転送 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "Turboism のパラメータ一括転送。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_KOREAN} "파라미터 일괄 전송 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_KOREAN} "Turboism 파라미터 일괄 전송 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_ENGLISH} "Parameter Sync Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_ENGLISH} "Synchronize parameter values in real time with normalized range mapping across checked parameters."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_SIMPCHINESE} "参数同步插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_SIMPCHINESE} "基于归一化百分比进度的参数数值实时同步联动插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_JAPANESE} "パラメータ同期プラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_JAPANESE} "正規化範囲マッピングに基づくパラメータ値のリアルタイム同期プラグイン。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_KOREAN} "매개변수 동기화 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_KOREAN} "정규화 범위 매핑을 지원하는 매개변수 값 실시간 동기화 플러그인."
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Performance Statistics 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Real-time Cubism process performance charts: CPU, FPS, and JVM memory as an embedded panel and a standalone window."
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "性能统计 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "Turboism 的性能统计。"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "パフォーマンス統計 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "Turboism のパフォーマンス統計。"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_KOREAN} "성능 통계 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_KOREAN} "Turboism 성능 통계 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Physics Settings Enhancer 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Adds Physics Settings group select-all and reopen retention."
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "物理运算设置增强 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "为 Cubism 物理运算设置组列表新增表头全选与重开状态保留。"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "物理演算設定拡張 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "Cubism の物理演算設定グループリストに表頭すべて選択と再表示時の状態保持を追加します。"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_KOREAN} "물리 설정 향상기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_KOREAN} "Cubism 물리 설정 그룹 목록에 헤더 전체 선택 및 창 다시 열기 시 상태 유지를 추가합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_ENGLISH} "Protected Export 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_ENGLISH} "Optional protected runtime-model export: works on a task-owned copy, flattens Warp/Rotation deformers, obfuscates ArtMesh identities, validates the complete staged output, then publishes atomically. Admitted only on reviewed Cubism versions and eligible models."
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_SIMPCHINESE} "受保护导出 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_SIMPCHINESE} "可选的受保护运行时模型导出：在任务专用副本上扁平化 Warp/Rotation 变形器、混淆 ArtMesh 标识、验证完整暂存输出后原子发布；仅对审查通过的 Cubism 版本与满足准入检查的模型生效。"
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_JAPANESE} "保護付きエクスポート 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_JAPANESE} "オプションの保護付きランタイムモデルエクスポートです。タスク専用コピー上でWarp/回転デフォーマを平坦化し、ArtMesh識別子を難読化し、完全なステージング出力を検証してからアトミックに公開します。レビュー済みのCubismバージョンと適格なモデルでのみ有効です。"
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_KOREAN} "보호된 내보내기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_KOREAN} "선택적 보호 런타임 모델 내보내기입니다. 작업 전용 복사본에서 Warp/회전 디포머를 평탄화하고 ArtMesh 식별자를 난독화하며, 완성된 스테이징 출력을 검증한 뒤 원자적으로 게시합니다. 검토된 Cubism 버전과 적격 모델에서만 동작합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "PSD Clip Mask Import Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "Imports ordered PSD clipping relationships into ArtMesh clip-mask assignments with an explicit overwrite policy."
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "PSD 剪贴蒙版导入插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "按顺序将 PSD 剪贴关系导入 ArtMesh 剪贴蒙版分配，并提供明确的覆盖策略。"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD クリッピングマスクインポートプラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD のクリッピング関係を順序どおり ArtMesh のクリッピングマスク割り当てへ取り込み、明示的な上書き方針を提供します。"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_KOREAN} "PSD 클립 마스크 가져오기 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_KOREAN} "PSD 클리핑 관계를 순서대로 ArtMesh 클립 마스크 할당으로 가져오며 명시적인 덮어쓰기 정책을 제공합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Recent Preview 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Captures bounded preview thumbnails for recent project files and contributes them to the Recent Files hover popup."
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "最近预览 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "Turboism 的最近预览。"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "最近のプレビュー 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "Turboism の最近のプレビュー。"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_KOREAN} "최근 미리보기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_KOREAN} "Turboism 최근 미리보기 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Scene Palette Enhancer 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Sorts and manually reorders items in the Cubism Scene palette."
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "场景面板增强器 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "对 Cubism 场景面板中的项目进行排序与手动重新排序。"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "シーンパレット拡張 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "Cubism シーンパレットの項目をソートおよび手動で並べ替えます。"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_KOREAN} "장면 팔레트 향상기 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_KOREAN} "Turboism 장면 팔레트 향상기 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_ENGLISH} "Selection Brush 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_ENGLISH} "Select ArtMesh vertices and Warp Deformer control points with an adjustable-radius brush."
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_SIMPCHINESE} "选区笔刷 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_SIMPCHINESE} "使用可调半径的笔刷选择 ArtMesh 顶点和曲面变形器控制点。"
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_JAPANESE} "選択ブラシ 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_JAPANESE} "半径を調整できるブラシで ArtMesh の頂点とワープデフォーマの制御点を選択します。"
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_KOREAN} "선택 브러시 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_KOREAN} "반경을 조절할 수 있는 브러시로 ArtMesh 정점과 워프 디포머의 제어점을 선택합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "MaxRects-BSSF Layout Algorithm 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "Registers the MaxRects-BSSF texture-atlas packing algorithm with parallel search."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "MaxRects-BSSF 布局算法 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "Turboism 的MaxRects-BSSF 布局算法。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "MaxRects-BSSF レイアウトアルゴリズム 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "Turboism のMaxRects-BSSF レイアウトアルゴリズム。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_KOREAN} "MaxRects-BSSF 레이아웃 알고리즘 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_KOREAN} "Turboism MaxRects-BSSF 레이아웃 알고리즘 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_ENGLISH} "Dalsoo Polygon Packing Algorithm 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_ENGLISH} "Irregular-polygon texture-atlas packing (concave outlines, free rotation, per-item locks)."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_SIMPCHINESE} "Dalsoo 多边形排版算法 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_SIMPCHINESE} "不规则多边形纹理集排版（支持凹轮廓、自由角度、逐项锁定）。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_JAPANESE} "Dalsoo ポリゴン充填アルゴリズム 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_JAPANESE} "不規則ポリゴンのテクスチャアトラス充填（凹み輪郭・自由回転・アイテム単位のロック対応）。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_KOREAN} "Dalsoo 폴리곤 패킹 알고리즘 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_KOREAN} "불규칙 폴리곤 텍스처 아틀라스 패킹(오목 윤곽, 자유 회전, 항목별 잠금 지원)."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_ENGLISH} "Folder Atlas Layout 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_ENGLISH} "Arrange texture pages by Part folders using registered packing algorithms."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_SIMPCHINESE} "纹理集一键排版 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_SIMPCHINESE} "按部件文件夹分治生成多张纹理，自动发现已注册的排版算法。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_JAPANESE} "フォルダー別アトラス配置 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_JAPANESE} "登録済み配置アルゴリズムを使い、パーツフォルダー別にアトラスを作成します。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_KOREAN} "폴더별 아틀라스 배치 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_KOREAN} "등록된 배치 알고리즘으로 파츠 폴더별 텍스처 페이지를 생성합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_ENGLISH} "Bitmask Nesting 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_ENGLISH} "Dense texture-atlas packing with concave outlines and multiple rotation angles."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_SIMPCHINESE} "Bitmask Nesting 密集轮廓排版 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_SIMPCHINESE} "利用凹轮廓和多个旋转角度进行密集纹理排版。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_JAPANESE} "Bitmask Nesting 輪郭配置 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_JAPANESE} "凹形状の輪郭と複数の回転角度を使って、テクスチャを高密度に配置します。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_KOREAN} "Bitmask Nesting 윤곽 배치 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_KOREAN} "오목한 윤곽과 여러 회전 각도를 사용해 텍스처를 조밀하게 배치합니다."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Texture Atlas Statistics 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Shows the total and current-texture model-image counts in the native texture-atlas editor window."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "纹理图集统计 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "Turboism 的纹理图集统计。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "テクスチャアトラス統計 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "Turboism のテクスチャアトラス統計。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_KOREAN} "텍스처 아틀라스 통계 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_KOREAN} "Turboism 텍스처 아틀라스 통계 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "UI Theme Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "Legacy-compatible theme packages, built-in themes, theme manager workflow, and exact-version Cubism appearance application."
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "UI 主题插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "Turboism的UI 主题插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "UI テーマプラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "TurboismのUI テーマプラグイン。"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_KOREAN} "UI 테마 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_KOREAN} "Turboism UI 테마 플러그인입니다."
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_ENGLISH} "Warp Deformer Alt Symmetry 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_ENGLISH} "Button-controlled mirror editing for Warp Deformer control points."
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_SIMPCHINESE} "曲面变形器 Alt 对称 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_SIMPCHINESE} "通过画布按钮切换曲面变形器控制点的镜像编辑。"
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_JAPANESE} "ワープデフォーマ Alt 対称 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_JAPANESE} "キャンバスのボタンでワープデフォーマ制御点のミラー編集を切り替えます。"
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_KOREAN} "워프 디포머 Alt 대칭 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_KOREAN} "캔버스 버튼으로 워프 디포머와 아트메시의 미러 편집을 전환합니다. 아트메시 대칭축을 이동하고 회전할 수 있습니다."
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_ENGLISH} "WebDAV Auto-Backup Sync Plugin 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_ENGLISH} "Uploads Cubism auto-backup artifacts to a WebDAV endpoint (JDK HttpClient only)."
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_SIMPCHINESE} "WebDAV 自动备份同步插件 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_SIMPCHINESE} "Turboism的WebDAV 自动备份同步插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_JAPANESE} "WebDAV 自動バックアップ同期プラグイン 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_JAPANESE} "TurboismのWebDAV 自動バックアップ同期プラグイン。"
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_KOREAN} "WebDAV 자동 백업 동기화 플러그인 0.68.1"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_KOREAN} "Turboism WebDAV 자동 백업 동기화 플러그인입니다."

; 初始化第一方插件选择状态（默认全选）
Function InitPluginSelectionDefaults
  StrCpy $PluginTotalCount "32"
  StrCpy $PluginSelectedCount "32"
  StrCpy $PluginCurrentIndex "0"
  StrCpy $PluginReadmeDir ""
  StrCpy $PluginState_0 1
  StrCpy $PluginState_1 1
  StrCpy $PluginState_2 1
  StrCpy $PluginState_3 1
  StrCpy $PluginState_4 1
  StrCpy $PluginState_5 1
  StrCpy $PluginState_6 1
  StrCpy $PluginState_7 1
  StrCpy $PluginState_8 1
  StrCpy $PluginState_9 1
  StrCpy $PluginState_10 1
  StrCpy $PluginState_11 1
  StrCpy $PluginState_12 1
  StrCpy $PluginState_13 1
  StrCpy $PluginState_14 1
  StrCpy $PluginState_15 1
  StrCpy $PluginState_16 1
  StrCpy $PluginState_17 1
  StrCpy $PluginState_18 1
  StrCpy $PluginState_19 1
  StrCpy $PluginState_20 1
  StrCpy $PluginState_21 1
  StrCpy $PluginState_22 1
  StrCpy $PluginState_23 1
  StrCpy $PluginState_24 1
  StrCpy $PluginState_25 1
  StrCpy $PluginState_26 1
  StrCpy $PluginState_27 1
  StrCpy $PluginState_28 1
  StrCpy $PluginState_29 1
  StrCpy $PluginState_30 1
  StrCpy $PluginState_31 1
FunctionEnd

; 按当前界面语言解压插件渲染后的 RTF README 到安装器私有临时目录
Function StagePluginReadmeFiles
  ${If} $PluginReadmeDir != ""
    ${If} ${FileExists} "$PluginReadmeDir\plugin-0.rtf"
      Return
    ${EndIf}
  ${EndIf}
  InitPluginsDir
  StrCpy $PluginReadmeDir "$PLUGINSDIR\Turboism-plugin-readmes"
  SetOutPath "$PluginReadmeDir"
  ${If} $LANGUAGE == ${LANG_SIMPCHINESE}
    File "/oname=plugin-0.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0000-chn.rtf"
    File "/oname=plugin-1.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0001-chn.rtf"
    File "/oname=plugin-2.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0002-chn.rtf"
    File "/oname=plugin-3.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0003-chn.rtf"
    File "/oname=plugin-4.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0004-chn.rtf"
    File "/oname=plugin-5.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0005-chn.rtf"
    File "/oname=plugin-6.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0006-chn.rtf"
    File "/oname=plugin-7.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0007-chn.rtf"
    File "/oname=plugin-8.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0008-chn.rtf"
    File "/oname=plugin-9.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0009-chn.rtf"
    File "/oname=plugin-10.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0010-chn.rtf"
    File "/oname=plugin-11.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0011-chn.rtf"
    File "/oname=plugin-12.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0012-chn.rtf"
    File "/oname=plugin-13.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0013-chn.rtf"
    File "/oname=plugin-14.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0014-chn.rtf"
    File "/oname=plugin-15.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0015-chn.rtf"
    File "/oname=plugin-16.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0016-chn.rtf"
    File "/oname=plugin-17.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0017-chn.rtf"
    File "/oname=plugin-18.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0018-chn.rtf"
    File "/oname=plugin-19.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0019-chn.rtf"
    File "/oname=plugin-20.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0020-chn.rtf"
    File "/oname=plugin-21.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0021-chn.rtf"
    File "/oname=plugin-22.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0022-chn.rtf"
    File "/oname=plugin-23.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0023-chn.rtf"
    File "/oname=plugin-24.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0024-chn.rtf"
    File "/oname=plugin-25.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0025-chn.rtf"
    File "/oname=plugin-26.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0026-chn.rtf"
    File "/oname=plugin-27.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0027-chn.rtf"
    File "/oname=plugin-28.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0028-chn.rtf"
    File "/oname=plugin-29.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0029-chn.rtf"
    File "/oname=plugin-30.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0030-chn.rtf"
    File "/oname=plugin-31.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0031-chn.rtf"
  ${ElseIf} $LANGUAGE == ${LANG_JAPANESE}
    File "/oname=plugin-0.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0000-jpn.rtf"
    File "/oname=plugin-1.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0001-jpn.rtf"
    File "/oname=plugin-2.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0002-jpn.rtf"
    File "/oname=plugin-3.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0003-jpn.rtf"
    File "/oname=plugin-4.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0004-jpn.rtf"
    File "/oname=plugin-5.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0005-jpn.rtf"
    File "/oname=plugin-6.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0006-jpn.rtf"
    File "/oname=plugin-7.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0007-jpn.rtf"
    File "/oname=plugin-8.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0008-jpn.rtf"
    File "/oname=plugin-9.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0009-jpn.rtf"
    File "/oname=plugin-10.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0010-jpn.rtf"
    File "/oname=plugin-11.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0011-jpn.rtf"
    File "/oname=plugin-12.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0012-jpn.rtf"
    File "/oname=plugin-13.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0013-jpn.rtf"
    File "/oname=plugin-14.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0014-jpn.rtf"
    File "/oname=plugin-15.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0015-jpn.rtf"
    File "/oname=plugin-16.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0016-jpn.rtf"
    File "/oname=plugin-17.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0017-jpn.rtf"
    File "/oname=plugin-18.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0018-jpn.rtf"
    File "/oname=plugin-19.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0019-jpn.rtf"
    File "/oname=plugin-20.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0020-jpn.rtf"
    File "/oname=plugin-21.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0021-jpn.rtf"
    File "/oname=plugin-22.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0022-jpn.rtf"
    File "/oname=plugin-23.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0023-jpn.rtf"
    File "/oname=plugin-24.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0024-jpn.rtf"
    File "/oname=plugin-25.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0025-jpn.rtf"
    File "/oname=plugin-26.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0026-jpn.rtf"
    File "/oname=plugin-27.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0027-jpn.rtf"
    File "/oname=plugin-28.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0028-jpn.rtf"
    File "/oname=plugin-29.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0029-jpn.rtf"
    File "/oname=plugin-30.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0030-jpn.rtf"
    File "/oname=plugin-31.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0031-jpn.rtf"
  ${ElseIf} $LANGUAGE == ${LANG_KOREAN}
    File "/oname=plugin-0.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0000-kor.rtf"
    File "/oname=plugin-1.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0001-kor.rtf"
    File "/oname=plugin-2.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0002-kor.rtf"
    File "/oname=plugin-3.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0003-kor.rtf"
    File "/oname=plugin-4.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0004-kor.rtf"
    File "/oname=plugin-5.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0005-kor.rtf"
    File "/oname=plugin-6.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0006-kor.rtf"
    File "/oname=plugin-7.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0007-kor.rtf"
    File "/oname=plugin-8.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0008-kor.rtf"
    File "/oname=plugin-9.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0009-kor.rtf"
    File "/oname=plugin-10.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0010-kor.rtf"
    File "/oname=plugin-11.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0011-kor.rtf"
    File "/oname=plugin-12.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0012-kor.rtf"
    File "/oname=plugin-13.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0013-kor.rtf"
    File "/oname=plugin-14.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0014-kor.rtf"
    File "/oname=plugin-15.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0015-kor.rtf"
    File "/oname=plugin-16.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0016-kor.rtf"
    File "/oname=plugin-17.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0017-kor.rtf"
    File "/oname=plugin-18.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0018-kor.rtf"
    File "/oname=plugin-19.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0019-kor.rtf"
    File "/oname=plugin-20.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0020-kor.rtf"
    File "/oname=plugin-21.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0021-kor.rtf"
    File "/oname=plugin-22.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0022-kor.rtf"
    File "/oname=plugin-23.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0023-kor.rtf"
    File "/oname=plugin-24.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0024-kor.rtf"
    File "/oname=plugin-25.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0025-kor.rtf"
    File "/oname=plugin-26.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0026-kor.rtf"
    File "/oname=plugin-27.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0027-kor.rtf"
    File "/oname=plugin-28.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0028-kor.rtf"
    File "/oname=plugin-29.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0029-kor.rtf"
    File "/oname=plugin-30.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0030-kor.rtf"
    File "/oname=plugin-31.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0031-kor.rtf"
  ${Else}
    File "/oname=plugin-0.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0000-eng.rtf"
    File "/oname=plugin-1.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0001-eng.rtf"
    File "/oname=plugin-2.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0002-eng.rtf"
    File "/oname=plugin-3.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0003-eng.rtf"
    File "/oname=plugin-4.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0004-eng.rtf"
    File "/oname=plugin-5.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0005-eng.rtf"
    File "/oname=plugin-6.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0006-eng.rtf"
    File "/oname=plugin-7.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0007-eng.rtf"
    File "/oname=plugin-8.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0008-eng.rtf"
    File "/oname=plugin-9.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0009-eng.rtf"
    File "/oname=plugin-10.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0010-eng.rtf"
    File "/oname=plugin-11.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0011-eng.rtf"
    File "/oname=plugin-12.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0012-eng.rtf"
    File "/oname=plugin-13.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0013-eng.rtf"
    File "/oname=plugin-14.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0014-eng.rtf"
    File "/oname=plugin-15.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0015-eng.rtf"
    File "/oname=plugin-16.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0016-eng.rtf"
    File "/oname=plugin-17.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0017-eng.rtf"
    File "/oname=plugin-18.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0018-eng.rtf"
    File "/oname=plugin-19.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0019-eng.rtf"
    File "/oname=plugin-20.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0020-eng.rtf"
    File "/oname=plugin-21.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0021-eng.rtf"
    File "/oname=plugin-22.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0022-eng.rtf"
    File "/oname=plugin-23.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0023-eng.rtf"
    File "/oname=plugin-24.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0024-eng.rtf"
    File "/oname=plugin-25.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0025-eng.rtf"
    File "/oname=plugin-26.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0026-eng.rtf"
    File "/oname=plugin-27.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0027-eng.rtf"
    File "/oname=plugin-28.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0028-eng.rtf"
    File "/oname=plugin-29.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0029-eng.rtf"
    File "/oname=plugin-30.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0030-eng.rtf"
    File "/oname=plugin-31.rtf" "${GENERATED_DIR}/plugin-readmes/plugin-0031-eng.rtf"
  ${EndIf}
FunctionEnd

; 填充第一方插件复选框列表（SysListView32 + LVS_EX_CHECKBOXES）
Function PopulatePluginSelectionList
  ; LVM_DELETEALLITEMS = 0x1009
  SendMessage $PluginSelectionList 0x1009 0 0
  StrCpy $0 0
  StrCpy $1 $PluginState_0
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_acp)"
  Call InsertPluginListViewItem
  StrCpy $0 1
  StrCpy $1 $PluginState_1
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror)"
  Call InsertPluginListViewItem
  StrCpy $0 2
  StrCpy $1 $PluginState_2
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror)"
  Call InsertPluginListViewItem
  StrCpy $0 3
  StrCpy $1 $PluginState_3
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer)"
  Call InsertPluginListViewItem
  StrCpy $0 4
  StrCpy $1 $PluginState_4
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_command_palette)"
  Call InsertPluginListViewItem
  StrCpy $0 5
  StrCpy $1 $PluginState_5
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter)"
  Call InsertPluginListViewItem
  StrCpy $0 6
  StrCpy $1 $PluginState_6
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename)"
  Call InsertPluginListViewItem
  StrCpy $0 7
  StrCpy $1 $PluginState_7
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_external_psd_edit)"
  Call InsertPluginListViewItem
  StrCpy $0 8
  StrCpy $1 $PluginState_8
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_frame_animation_import)"
  Call InsertPluginListViewItem
  StrCpy $0 9
  StrCpy $1 $PluginState_9
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_geometric_mesh_layout)"
  Call InsertPluginListViewItem
  StrCpy $0 10
  StrCpy $1 $PluginState_10
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_historypanel)"
  Call InsertPluginListViewItem
  StrCpy $0 11
  StrCpy $1 $PluginState_11
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_mcp)"
  Call InsertPluginListViewItem
  StrCpy $0 12
  StrCpy $1 $PluginState_12
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance)"
  Call InsertPluginListViewItem
  StrCpy $0 13
  StrCpy $1 $PluginState_13
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_model_import_3d)"
  Call InsertPluginListViewItem
  StrCpy $0 14
  StrCpy $1 $PluginState_14
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_palette_label_style)"
  Call InsertPluginListViewItem
  StrCpy $0 15
  StrCpy $1 $PluginState_15
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer)"
  Call InsertPluginListViewItem
  StrCpy $0 16
  StrCpy $1 $PluginState_16
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_parameter_sync)"
  Call InsertPluginListViewItem
  StrCpy $0 17
  StrCpy $1 $PluginState_17
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_perf_stats)"
  Call InsertPluginListViewItem
  StrCpy $0 18
  StrCpy $1 $PluginState_18
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_physics_editor)"
  Call InsertPluginListViewItem
  StrCpy $0 19
  StrCpy $1 $PluginState_19
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_protected_export)"
  Call InsertPluginListViewItem
  StrCpy $0 20
  StrCpy $1 $PluginState_20
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import)"
  Call InsertPluginListViewItem
  StrCpy $0 21
  StrCpy $1 $PluginState_21
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_recent_preview)"
  Call InsertPluginListViewItem
  StrCpy $0 22
  StrCpy $1 $PluginState_22
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer)"
  Call InsertPluginListViewItem
  StrCpy $0 23
  StrCpy $1 $PluginState_23
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_selection_brush)"
  Call InsertPluginListViewItem
  StrCpy $0 24
  StrCpy $1 $PluginState_24
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas)"
  Call InsertPluginListViewItem
  StrCpy $0 25
  StrCpy $1 $PluginState_25
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo)"
  Call InsertPluginListViewItem
  StrCpy $0 26
  StrCpy $1 $PluginState_26
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders)"
  Call InsertPluginListViewItem
  StrCpy $0 27
  StrCpy $1 $PluginState_27
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting)"
  Call InsertPluginListViewItem
  StrCpy $0 28
  StrCpy $1 $PluginState_28
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats)"
  Call InsertPluginListViewItem
  StrCpy $0 29
  StrCpy $1 $PluginState_29
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_uitheme)"
  Call InsertPluginListViewItem
  StrCpy $0 30
  StrCpy $1 $PluginState_30
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry)"
  Call InsertPluginListViewItem
  StrCpy $0 31
  StrCpy $1 $PluginState_31
  StrCpy $2 "$(PLUGIN_NAME_dev_turboism_plugin_webdav)"
  Call InsertPluginListViewItem
  System::Call '*(i 8, i $PluginCurrentIndex, i 0, i 3, i 3, p 0, i 0, i 0, p 0) p .r3'
  ${If} $3 != 0
    SendMessage $PluginSelectionList 0x102B $PluginCurrentIndex $3
    System::Free $3
  ${EndIf}
FunctionEnd

; 从 SysListView32 同步原生复选框勾选状态（$4=总勾选数, $5=刚被点击复选框的索引或 -1）
Function SyncPluginSelectionFromListView
  StrCpy $4 0
  StrCpy $5 -1
  SendMessage $PluginSelectionList 0x102C 0 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_0
    StrCpy $PluginState_0 $1
    StrCpy $5 0
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 1 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_1
    StrCpy $PluginState_1 $1
    StrCpy $5 1
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 2 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_2
    StrCpy $PluginState_2 $1
    StrCpy $5 2
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 3 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_3
    StrCpy $PluginState_3 $1
    StrCpy $5 3
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 4 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_4
    StrCpy $PluginState_4 $1
    StrCpy $5 4
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 5 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_5
    StrCpy $PluginState_5 $1
    StrCpy $5 5
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 6 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_6
    StrCpy $PluginState_6 $1
    StrCpy $5 6
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 7 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_7
    StrCpy $PluginState_7 $1
    StrCpy $5 7
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 8 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_8
    StrCpy $PluginState_8 $1
    StrCpy $5 8
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 9 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_9
    StrCpy $PluginState_9 $1
    StrCpy $5 9
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 10 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_10
    StrCpy $PluginState_10 $1
    StrCpy $5 10
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 11 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_11
    StrCpy $PluginState_11 $1
    StrCpy $5 11
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 12 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_12
    StrCpy $PluginState_12 $1
    StrCpy $5 12
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 13 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_13
    StrCpy $PluginState_13 $1
    StrCpy $5 13
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 14 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_14
    StrCpy $PluginState_14 $1
    StrCpy $5 14
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 15 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_15
    StrCpy $PluginState_15 $1
    StrCpy $5 15
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 16 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_16
    StrCpy $PluginState_16 $1
    StrCpy $5 16
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 17 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_17
    StrCpy $PluginState_17 $1
    StrCpy $5 17
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 18 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_18
    StrCpy $PluginState_18 $1
    StrCpy $5 18
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 19 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_19
    StrCpy $PluginState_19 $1
    StrCpy $5 19
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 20 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_20
    StrCpy $PluginState_20 $1
    StrCpy $5 20
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 21 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_21
    StrCpy $PluginState_21 $1
    StrCpy $5 21
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 22 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_22
    StrCpy $PluginState_22 $1
    StrCpy $5 22
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 23 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_23
    StrCpy $PluginState_23 $1
    StrCpy $5 23
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 24 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_24
    StrCpy $PluginState_24 $1
    StrCpy $5 24
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 25 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_25
    StrCpy $PluginState_25 $1
    StrCpy $5 25
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 26 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_26
    StrCpy $PluginState_26 $1
    StrCpy $5 26
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 27 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_27
    StrCpy $PluginState_27 $1
    StrCpy $5 27
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 28 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_28
    StrCpy $PluginState_28 $1
    StrCpy $5 28
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 29 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_29
    StrCpy $PluginState_29 $1
    StrCpy $5 29
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 30 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_30
    StrCpy $PluginState_30 $1
    StrCpy $5 30
  ${EndIf}
  IntOp $4 $4 + $1
  SendMessage $PluginSelectionList 0x102C 31 0xF000 $1
  IntOp $1 $1 & 0xF000
  ${If} $1 == 8192
    StrCpy $1 1
  ${Else}
    StrCpy $1 0
  ${EndIf}
  ${If} $1 != $PluginState_31
    StrCpy $PluginState_31 $1
    StrCpy $5 31
  ${EndIf}
  IntOp $4 $4 + $1
FunctionEnd

; 全选 ($0=1) 或全不选 ($0=0) 全部第一方插件
Function SetAllPluginsSelected
  StrCpy $PluginState_0 $0
  StrCpy $PluginState_1 $0
  StrCpy $PluginState_2 $0
  StrCpy $PluginState_3 $0
  StrCpy $PluginState_4 $0
  StrCpy $PluginState_5 $0
  StrCpy $PluginState_6 $0
  StrCpy $PluginState_7 $0
  StrCpy $PluginState_8 $0
  StrCpy $PluginState_9 $0
  StrCpy $PluginState_10 $0
  StrCpy $PluginState_11 $0
  StrCpy $PluginState_12 $0
  StrCpy $PluginState_13 $0
  StrCpy $PluginState_14 $0
  StrCpy $PluginState_15 $0
  StrCpy $PluginState_16 $0
  StrCpy $PluginState_17 $0
  StrCpy $PluginState_18 $0
  StrCpy $PluginState_19 $0
  StrCpy $PluginState_20 $0
  StrCpy $PluginState_21 $0
  StrCpy $PluginState_22 $0
  StrCpy $PluginState_23 $0
  StrCpy $PluginState_24 $0
  StrCpy $PluginState_25 $0
  StrCpy $PluginState_26 $0
  StrCpy $PluginState_27 $0
  StrCpy $PluginState_28 $0
  StrCpy $PluginState_29 $0
  StrCpy $PluginState_30 $0
  StrCpy $PluginState_31 $0
  ${If} $0 == 1
    StrCpy $PluginSelectedCount "32"
    StrCpy $4 0x2000
  ${Else}
    StrCpy $PluginSelectedCount "0"
    StrCpy $4 0x1000
  ${EndIf}
  System::Call '*(i 8, i 0, i 0, i r4, i 0xF000, p 0, i 0, i 0, p 0) p .r3'
  ${If} $3 != 0
    SendMessage $PluginSelectionList 0x102B -1 $3
    System::Free $3
  ${EndIf}
FunctionEnd

; 为未勾选的第一方插件写入跳过标记，并移除此前已安装的对应捆绑 JAR
Function WritePluginSelectionSkipMarkers
  CreateDirectory "$PLUGINSDIR\Turboism-plugin-plan"
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0000.skip"
  ${If} $PluginState_0 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0000.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\acp.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0001.skip"
  ${If} $PluginState_1 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0001.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\artmesh-mirror.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0002.skip"
  ${If} $PluginState_2 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0002.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\boundingbox-warp-mirror.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0003.skip"
  ${If} $PluginState_3 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0003.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\clipmask-viewer.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0004.skip"
  ${If} $PluginState_4 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0004.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\command-palette.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0005.skip"
  ${If} $PluginState_5 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0005.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\cubism-tab-filter.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0006.skip"
  ${If} $PluginState_6 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0006.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\deformer-batch-rename.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0007.skip"
  ${If} $PluginState_7 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0007.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\external-psd-edit.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0008.skip"
  ${If} $PluginState_8 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0008.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\frame-animation-import.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0009.skip"
  ${If} $PluginState_9 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0009.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\geometric-mesh-layout.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0010.skip"
  ${If} $PluginState_10 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0010.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\history-panel.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0011.skip"
  ${If} $PluginState_11 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0011.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\mcp.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0012.skip"
  ${If} $PluginState_12 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0012.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\mesh-edit-mirror-axis-enhance.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0013.skip"
  ${If} $PluginState_13 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0013.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\model-import-3d.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0014.skip"
  ${If} $PluginState_14 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0014.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\palette-label-style.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0015.skip"
  ${If} $PluginState_15 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0015.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\parameter-batch-transfer.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0016.skip"
  ${If} $PluginState_16 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0016.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\parameter-sync.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0017.skip"
  ${If} $PluginState_17 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0017.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\perf-stats.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0018.skip"
  ${If} $PluginState_18 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0018.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\physics-editor.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0019.skip"
  ${If} $PluginState_19 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0019.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\protected-export.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0020.skip"
  ${If} $PluginState_20 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0020.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\psd-clip-mask-import.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0021.skip"
  ${If} $PluginState_21 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0021.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\recent-preview.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0022.skip"
  ${If} $PluginState_22 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0022.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\scene-palette-enhancer.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0023.skip"
  ${If} $PluginState_23 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0023.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\selection-brush.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0024.skip"
  ${If} $PluginState_24 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0024.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\atlas-maxrects-bssf.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0025.skip"
  ${If} $PluginState_25 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0025.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\atlas-dalsoo-polygon.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0026.skip"
  ${If} $PluginState_26 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0026.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\atlas-folder-layout.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0027.skip"
  ${If} $PluginState_27 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0027.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\atlas-bitmask-nesting.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0028.skip"
  ${If} $PluginState_28 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0028.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\texture-atlas-stats.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0029.skip"
  ${If} $PluginState_29 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0029.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\ui-theme.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0030.skip"
  ${If} $PluginState_30 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0030.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\warp-deformer-alt-symmetry.jar"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-plugin-plan\0031.skip"
  ${If} $PluginState_31 == 0
    FileOpen $0 "$PLUGINSDIR\Turboism-plugin-plan\0031.skip" w
    FileClose $0
    Delete "$INSTDIR\plugins\webdav-backup.jar"
  ${EndIf}
FunctionEnd

; 导出完整捆绑插件 id 清单，供前置配置提交保留无关禁用项。
Function SetBundledPluginIds
  StrCpy $bundledPluginIds "dev.turboism.plugin.acp;dev.turboism.plugin.artmesh-mirror;dev.turboism.plugin.boundingbox-warp-mirror;dev.turboism.plugin.clipmask-viewer;dev.turboism.plugin.command-palette;dev.turboism.plugin.cubism-tab-filter;dev.turboism.plugin.deformer-batch-rename;dev.turboism.plugin.external-psd-edit;dev.turboism.plugin.frame-animation-import;dev.turboism.plugin.geometric-mesh-layout;dev.turboism.plugin.historypanel;dev.turboism.plugin.mcp;dev.turboism.plugin.mesh-edit-mirror-axis-enhance;dev.turboism.plugin.model-import-3d;dev.turboism.plugin.palette-label-style;dev.turboism.plugin.parameter-batch-transfer;dev.turboism.plugin.parameter-sync;dev.turboism.plugin.perf-stats;dev.turboism.plugin.physics-editor;dev.turboism.plugin.protected-export;dev.turboism.plugin.psd-clip-mask-import;dev.turboism.plugin.recent-preview;dev.turboism.plugin.scene-palette-enhancer;dev.turboism.plugin.selection-brush;dev.turboism.plugin.texture-atlas;dev.turboism.plugin.texture-atlas-dalsoo;dev.turboism.plugin.texture-atlas-folders;dev.turboism.plugin.texture-atlas-nesting;dev.turboism.plugin.texture-atlas-stats;dev.turboism.plugin.uitheme;dev.turboism.plugin.warp-deformer-alt-symmetry;dev.turboism.plugin.webdav"
FunctionEnd

; 卸载白名单：仅删除安装器部署的插件 JAR；未知文件与第三方 JAR 保留。
Function un.DeleteInstallerPluginJars
  Delete "$INSTDIR\plugins\acp.jar"
  Delete "$INSTDIR\plugins\artmesh-mirror.jar"
  Delete "$INSTDIR\plugins\atlas-bitmask-nesting.jar"
  Delete "$INSTDIR\plugins\atlas-dalsoo-polygon.jar"
  Delete "$INSTDIR\plugins\atlas-folder-layout.jar"
  Delete "$INSTDIR\plugins\atlas-maxrects-bssf.jar"
  Delete "$INSTDIR\plugins\boundingbox-warp-mirror.jar"
  Delete "$INSTDIR\plugins\clipmask-viewer.jar"
  Delete "$INSTDIR\plugins\command-palette.jar"
  Delete "$INSTDIR\plugins\cubism-tab-filter.jar"
  Delete "$INSTDIR\plugins\deformer-batch-rename.jar"
  Delete "$INSTDIR\plugins\external-psd-edit.jar"
  Delete "$INSTDIR\plugins\frame-animation-import.jar"
  Delete "$INSTDIR\plugins\geometric-mesh-layout.jar"
  Delete "$INSTDIR\plugins\history-panel.jar"
  Delete "$INSTDIR\plugins\mcp.jar"
  Delete "$INSTDIR\plugins\mesh-edit-mirror-axis-enhance.jar"
  Delete "$INSTDIR\plugins\model-import-3d.jar"
  Delete "$INSTDIR\plugins\palette-label-style.jar"
  Delete "$INSTDIR\plugins\parameter-batch-transfer.jar"
  Delete "$INSTDIR\plugins\parameter-sync.jar"
  Delete "$INSTDIR\plugins\perf-stats.jar"
  Delete "$INSTDIR\plugins\physics-editor.jar"
  Delete "$INSTDIR\plugins\protected-export.jar"
  Delete "$INSTDIR\plugins\psd-clip-mask-import.jar"
  Delete "$INSTDIR\plugins\recent-preview.jar"
  Delete "$INSTDIR\plugins\scene-palette-enhancer.jar"
  Delete "$INSTDIR\plugins\selection-brush.jar"
  Delete "$INSTDIR\plugins\texture-atlas-stats.jar"
  Delete "$INSTDIR\plugins\ui-theme.jar"
  Delete "$INSTDIR\plugins\warp-deformer-alt-symmetry.jar"
  Delete "$INSTDIR\plugins\webdav-backup.jar"
FunctionEnd

