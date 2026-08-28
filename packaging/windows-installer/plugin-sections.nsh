; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。
; Full($Mode==1) 由隐藏载荷 Section 安装全部插件 JAR；可见 Section 只承载
; 勾选状态（disabledPlugins 元数据）；Lite 模式由 ModeLeave 取消全部可见 Section。

Section "-插件载荷" SecPluginPayload
  ${If} $Mode == 1
    SetOutPath "$INSTDIR\plugins"
    File "/oname=backup.jar" "${STAGING_DIR}/plugins/backup.jar"
    File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
    File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
    File "/oname=mcp.jar" "${STAGING_DIR}/plugins/mcp.jar"
    File "/oname=mesh-edit-mirror-axis-enhance.jar" "${STAGING_DIR}/plugins/mesh-edit-mirror-axis-enhance.jar"
    File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
    File "/oname=parameter-batch-transfer.jar" "${STAGING_DIR}/plugins/parameter-batch-transfer.jar"
    File "/oname=perf-stats.jar" "${STAGING_DIR}/plugins/perf-stats.jar"
    File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
    File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
    File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
    File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
    File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
    File "/oname=turboism-with-fx.jar" "${STAGING_DIR}/plugins/turboism-with-fx.jar"
    File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
  ${EndIf}
SectionEnd

Section "WebDAV Auto-Backup Sync Plugin 0.1.0" SEC_dev_turboism_plugin_backup
SectionEnd

Section "Clip Mask Viewer 0.1.0" SEC_dev_turboism_plugin_clipmask_viewer
SectionEnd

Section "Cubism Tab Filter 0.1.0" SEC_dev_turboism_plugin_cubism_tab_filter
SectionEnd

Section "Turboism MCP Server 0.1.0" SEC_dev_turboism_plugin_mcp
SectionEnd

Section "Mesh Edit Mirror Axis Enhance 0.1.0" SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance
SectionEnd

Section "Palette Label Style Plugin 0.1.0" SEC_dev_turboism_plugin_palette_label_style
SectionEnd

Section "Parameter Batch Transfer 0.1.0" SEC_dev_turboism_plugin_parameter_batch_transfer
SectionEnd

Section "Performance Statistics 0.1.0" SEC_dev_turboism_plugin_perf_stats
SectionEnd

Section "Physics Editor 0.1.0" SEC_dev_turboism_plugin_physics_editor
SectionEnd

Section "Recent Preview Plugin 0.1.0" SEC_dev_turboism_plugin_recent_preview
SectionEnd

Section "Scene Palette Enhancer 0.1.0" SEC_dev_turboism_plugin_scene_palette_enhancer
SectionEnd

Section "MaxRects-BSSF Layout Algorithm 0.1.0" SEC_dev_turboism_plugin_texture_atlas
SectionEnd

Section "Texture Atlas Statistics 0.1.0" SEC_dev_turboism_plugin_texture_atlas_stats
SectionEnd

Section "Turboism with fx 0.1.0" SEC_dev_turboism_plugin_turboism_with_fx
SectionEnd

Section "UI Theme Plugin 0.1.0" SEC_dev_turboism_plugin_uitheme
SectionEnd

; 组件页悬停描述
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_backup} "Uploads Cubism auto-backup artifacts to a WebDAV endpoint (JDK HttpClient only)."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_clipmask_viewer} "Read-only clip-mask duplicate checker and visualizer: Turboism tab entry, graph/table inspector window, editor selection highlight, GUID copy."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_cubism_tab_filter} "Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette tabs."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_mcp} "Loopback Model Context Protocol server exposing typed Cubism inspection, editing, history, and Editor-command workflows."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance} "Enhances Cubism mesh editing with mirror-axis rotation and exact-host mirror-tool parity."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_palette_label_style} "Label text and background colors for Deformer, Part, and Parameter palette entries via context menus."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_parameter_batch_transfer} "Batch-transfer parameter bindings of one ArtMesh or Deformer to other parameters, with optional inversion, through a modal dialog."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_perf_stats} "Real-time Cubism process performance charts: CPU, FPS, and JVM memory as an embedded panel and a standalone window."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_physics_editor} "Adds Physics Settings group select-all and reopen retention."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_recent_preview} "Captures bounded preview thumbnails for recent project files and contributes them to the Recent Files hover popup."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_scene_palette_enhancer} "Sorts and manually reorders items in the Cubism Scene palette."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas} "Registers the MaxRects-BSSF texture-atlas packing algorithm with parallel search."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas_stats} "Shows the total and current-texture model-image counts in the native texture-atlas editor window."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_turboism_with_fx} "ACP v1 Agent and Settings windows that connect Turboism's verified managed fx runtime to the authenticated MCP server."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_uitheme} "Legacy-compatible theme packages, built-in themes, theme manager workflow, and exact-version Cubism appearance application."
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
  SectionGetFlags ${SEC_dev_turboism_plugin_turboism_with_fx} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_turboism_with_fx} $1
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
  SectionGetFlags ${SEC_dev_turboism_plugin_turboism_with_fx} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.turboism-with-fx"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.turboism-with-fx"
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

; 从 $existingDisabled 中逐 id 移除全部当前捆绑插件 id（重选已捆绑插件即启用）。
; 每个 id 通过通用 RemoveItemFromList 辅助删除，避免长度受限的合并 id 字符串。
Function RemoveBundledFromExistingDisabled
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.backup"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.clipmask-viewer"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.cubism-tab-filter"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.mcp"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.mesh-edit-mirror-axis-enhance"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.palette-label-style"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.parameter-batch-transfer"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.perf-stats"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.physics-editor"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.recent-preview"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.scene-palette-enhancer"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.texture-atlas"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.texture-atlas-stats"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.turboism-with-fx"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
  StrCpy $0 "$existingDisabled"
  StrCpy $1 "dev.turboism.plugin.uitheme"
  Call RemoveItemFromList
  StrCpy $existingDisabled "$0"
FunctionEnd

