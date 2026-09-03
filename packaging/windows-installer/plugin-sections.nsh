; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。
; Full($Mode==1) 由隐藏载荷 Section 安装全部插件 JAR；可见 Section 只为
; 全新 config.json 收集 disabledPlugins；更新时 current schema 配置保持不变。

Section "-插件载荷" SecPluginPayload
  ${If} $Mode == 1
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR" -PlanRoot "$PLUGINSDIR\Turboism-plugin-plan" -PlanOnly'
    Pop $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP "$(PayloadInstallError)"
      Abort
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0000.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=backup.jar" "${STAGING_DIR}/plugins/backup.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0001.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0002.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0003.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=history-panel.jar" "${STAGING_DIR}/plugins/history-panel.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0004.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=mcp.jar" "${STAGING_DIR}/plugins/mcp.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0005.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=mesh-edit-mirror-axis-enhance.jar" "${STAGING_DIR}/plugins/mesh-edit-mirror-axis-enhance.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0006.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0007.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=parameter-batch-transfer.jar" "${STAGING_DIR}/plugins/parameter-batch-transfer.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0008.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=perf-stats.jar" "${STAGING_DIR}/plugins/perf-stats.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0009.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0010.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=psd-clip-mask-import.jar" "${STAGING_DIR}/plugins/psd-clip-mask-import.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0011.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0012.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0013.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0014.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
    ${EndIf}
    ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0015.need"
      SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
      File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
    ${EndIf}
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -SourceRoot "$PLUGINSDIR\Turboism-plugin-payload" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR"'
    Pop $0
    ${If} $0 != 0
      MessageBox MB_ICONSTOP "$(PayloadInstallError)"
      Abort
    ${EndIf}
    RMDir /r "$PLUGINSDIR\Turboism-plugin-payload"
    RMDir /r "$PLUGINSDIR\Turboism-plugin-plan"
  ${EndIf}
  Delete "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256"
  Delete "$PLUGINSDIR\Turboism-payload-manifests\payload-fx.sha256"
  RMDir "$PLUGINSDIR\Turboism-payload-manifests"
SectionEnd

LangString PLUGIN_NAME_dev_turboism_plugin_backup ${LANG_ENGLISH} "WebDAV Auto-Backup Sync Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_backup ${LANG_ENGLISH} "Uploads Cubism auto-backup artifacts to a WebDAV endpoint (JDK HttpClient only)."
LangString PLUGIN_NAME_dev_turboism_plugin_backup ${LANG_SIMPCHINESE} "WebDAV 自动备份同步插件 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_backup ${LANG_SIMPCHINESE} "Turboism的WebDAV 自动备份同步插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_backup ${LANG_JAPANESE} "WebDAV 自動バックアップ同期プラグイン 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_backup ${LANG_JAPANESE} "TurboismのWebDAV 自動バックアップ同期プラグイン。"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Clip Mask Viewer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Read-only clip-mask duplicate checker and visualizer: Turboism tab entry, graph/table inspector window, editor selection highlight, GUID copy."
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "剪裁蒙版查看器 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "Turboism 的剪裁蒙版查看器。"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "クリップマスクビューアー 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "Turboism のクリップマスクビューアー。"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Cubism Tab Filter 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette tabs."
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Cubism 标签筛选器 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Turboism 的Cubism 标签筛选器。"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Cubism タブフィルター 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Turboism のCubism タブフィルター。"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "History Panel Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "Photoshop-style history pane with native Undo history and snapshot-bound Undo/Redo navigation."
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "历史记录面板插件 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "Photoshop 风格历史面板，显示原生撤销历史并提供绑定快照的撤销/重做导航。"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "履歴パネルプラグイン 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "ネイティブ Undo 履歴とスナップショットに束縛された Undo/Redo ナビゲーションを提供する Photoshop 風履歴パネルです。"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Turboism MCP Server 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Loopback MCP server for typed Cubism inspection, editing, history, and Editor-command workflows."
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "Turboism MCP 服务器 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "通过类型化 MCP 工作流公开模型、参数、历史记录和编辑器命令的环回服务器。"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_JAPANESE} "Turboism MCP サーバー 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_JAPANESE} "モデル、パラメータ、履歴、エディターコマンドを型付き MCP ワークフローとして公開するループバックサーバー。"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Mesh Inspector and Mirror-Axis Tools 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Inspects mesh/deformer state and contributes the bounded mirror-axis rotation control for verified Cubism hosts."
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "网格检查与镜像轴工具 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "Turboism 的网格检查与镜像轴工具。"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "メッシュ検査とミラー軸ツール 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "Turboism のメッシュ検査とミラー軸ツール。"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Palette Label Style Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Label text and background colors for Deformer, Part, and Parameter palette entries via context menus."
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "调色板标签样式插件 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "Turboism的调色板标签样式插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "パレットラベルスタイルプラグイン 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "Turboismのパレットラベルスタイルプラグイン。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Parameter Batch Transfer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Batch-transfer parameter bindings of one ArtMesh or Deformer to other parameters, with optional inversion, through a modal dialog."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "参数批量传输 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "Turboism 的参数批量传输。"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "パラメータ一括転送 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "Turboism のパラメータ一括転送。"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Performance Statistics 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Real-time Cubism process performance charts: CPU, FPS, and JVM memory as an embedded panel and a standalone window."
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "性能统计 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "Turboism 的性能统计。"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "パフォーマンス統計 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "Turboism のパフォーマンス統計。"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Physics Editor 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Adds Physics Settings group select-all and reopen retention."
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "物理编辑器 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "Turboism 的物理编辑器。"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "物理演算エディター 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "Turboism の物理演算エディター。"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "PSD Clip Mask Import Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "Imports ordered PSD clipping relationships into ArtMesh clip-mask assignments with an explicit overwrite policy."
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "PSD 剪贴蒙版导入插件 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "按顺序将 PSD 剪贴关系导入 ArtMesh 剪贴蒙版分配，并提供明确的覆盖策略。"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD クリッピングマスクインポートプラグイン 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD のクリッピング関係を順序どおり ArtMesh のクリッピングマスク割り当てへ取り込み、明示的な上書き方針を提供します。"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Recent Preview 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Captures bounded preview thumbnails for recent project files and contributes them to the Recent Files hover popup."
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "最近预览 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "Turboism 的最近预览。"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "最近のプレビュー 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "Turboism の最近のプレビュー。"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Scene Palette Enhancer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Sorts and manually reorders items in the Cubism Scene palette."
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "场景调色板增强器 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "Turboism 的场景调色板增强器。"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "シーンパレット拡張 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "Turboism のシーンパレット拡張。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "MaxRects-BSSF Layout Algorithm 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "Registers the MaxRects-BSSF texture-atlas packing algorithm with parallel search."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "MaxRects-BSSF 布局算法 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "Turboism 的MaxRects-BSSF 布局算法。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "MaxRects-BSSF レイアウトアルゴリズム 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "Turboism のMaxRects-BSSF レイアウトアルゴリズム。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Texture Atlas Statistics 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Shows the total and current-texture model-image counts in the native texture-atlas editor window."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "纹理图集统计 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "Turboism 的纹理图集统计。"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "テクスチャアトラス統計 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "Turboism のテクスチャアトラス統計。"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "UI Theme Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "Legacy-compatible theme packages, built-in themes, theme manager workflow, and exact-version Cubism appearance application."
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "UI 主题插件 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "Turboism的UI 主题插件。"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "UI テーマプラグイン 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "TurboismのUI テーマプラグイン。"
Section "$(PLUGIN_NAME_dev_turboism_plugin_backup)" SEC_dev_turboism_plugin_backup
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer)" SEC_dev_turboism_plugin_clipmask_viewer
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter)" SEC_dev_turboism_plugin_cubism_tab_filter
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_historypanel)" SEC_dev_turboism_plugin_historypanel
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_mcp)" SEC_dev_turboism_plugin_mcp
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance)" SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_palette_label_style)" SEC_dev_turboism_plugin_palette_label_style
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer)" SEC_dev_turboism_plugin_parameter_batch_transfer
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_perf_stats)" SEC_dev_turboism_plugin_perf_stats
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_physics_editor)" SEC_dev_turboism_plugin_physics_editor
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import)" SEC_dev_turboism_plugin_psd_clip_mask_import
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_recent_preview)" SEC_dev_turboism_plugin_recent_preview
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer)" SEC_dev_turboism_plugin_scene_palette_enhancer
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas)" SEC_dev_turboism_plugin_texture_atlas
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats)" SEC_dev_turboism_plugin_texture_atlas_stats
SectionEnd

Section "$(PLUGIN_NAME_dev_turboism_plugin_uitheme)" SEC_dev_turboism_plugin_uitheme
SectionEnd

; 组件页悬停描述
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_backup} "$(PLUGIN_DESC_dev_turboism_plugin_backup)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_clipmask_viewer} "$(PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_cubism_tab_filter} "$(PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_historypanel} "$(PLUGIN_DESC_dev_turboism_plugin_historypanel)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_mcp} "$(PLUGIN_DESC_dev_turboism_plugin_mcp)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance} "$(PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_palette_label_style} "$(PLUGIN_DESC_dev_turboism_plugin_palette_label_style)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_parameter_batch_transfer} "$(PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_perf_stats} "$(PLUGIN_DESC_dev_turboism_plugin_perf_stats)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_physics_editor} "$(PLUGIN_DESC_dev_turboism_plugin_physics_editor)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_psd_clip_mask_import} "$(PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_recent_preview} "$(PLUGIN_DESC_dev_turboism_plugin_recent_preview)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_scene_palette_enhancer} "$(PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas} "$(PLUGIN_DESC_dev_turboism_plugin_texture_atlas)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas_stats} "$(PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_uitheme} "$(PLUGIN_DESC_dev_turboism_plugin_uitheme)"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; 按模式设置全部插件 Section 的选中状态（$0: 1 = 选中, 0 = 取消）
Function SetPluginSectionsSelected
  SectionGetFlags ${SEC_dev_turboism_plugin_backup} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_backup} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_clipmask_viewer} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_clipmask_viewer} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_cubism_tab_filter} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_cubism_tab_filter} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_historypanel} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_historypanel} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_mcp} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_mcp} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_palette_label_style} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_palette_label_style} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_parameter_batch_transfer} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_parameter_batch_transfer} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_perf_stats} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_perf_stats} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_physics_editor} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_physics_editor} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_psd_clip_mask_import} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_psd_clip_mask_import} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_recent_preview} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_recent_preview} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_scene_palette_enhancer} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_scene_palette_enhancer} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_texture_atlas} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_texture_atlas} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_texture_atlas_stats} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_texture_atlas_stats} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_uitheme} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_uitheme} $1
FunctionEnd

; 收集未勾选插件 id 到 $uncheckedPluginIds（';' 分隔）
Function CollectUncheckedPluginIds
  SectionGetFlags ${SEC_dev_turboism_plugin_backup} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.backup"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.backup"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_clipmask_viewer} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.clipmask-viewer"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.clipmask-viewer"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_cubism_tab_filter} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.cubism-tab-filter"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.cubism-tab-filter"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_historypanel} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.historypanel"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.historypanel"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_mcp} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.mcp"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.mcp"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.mesh-edit-mirror-axis-enhance"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.mesh-edit-mirror-axis-enhance"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_palette_label_style} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.palette-label-style"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.palette-label-style"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_parameter_batch_transfer} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.parameter-batch-transfer"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.parameter-batch-transfer"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_perf_stats} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.perf-stats"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.perf-stats"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_physics_editor} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.physics-editor"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.physics-editor"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_psd_clip_mask_import} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.psd-clip-mask-import"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.psd-clip-mask-import"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_recent_preview} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.recent-preview"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.recent-preview"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_scene_palette_enhancer} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.scene-palette-enhancer"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.scene-palette-enhancer"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_texture_atlas} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.texture-atlas"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.texture-atlas"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_texture_atlas_stats} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.texture-atlas-stats"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.texture-atlas-stats"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_uitheme} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.uitheme"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.uitheme"
    ${EndIf}
  ${EndIf}
FunctionEnd

