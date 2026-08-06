; 由 assemble-release.sh 从插件 jar 的 META-INF/turboism/plugin.json 生成，勿手改。
; 每个插件一个 Section；Lite 模式由 ModeLeave 取消选中，Section 体不执行。

Section "Bounding Box Plugin 0.1.0" SEC_dev_turboism_plugin_bounding_box
  SetOutPath "$INSTDIR\plugins"
  File "/oname=bounding-box.jar" "${STAGING_DIR}/plugins/bounding-box.jar"
SectionEnd

Section "Clip Mask Inspector Plugin 0.1.0" SEC_dev_turboism_plugin_clipmask
  SetOutPath "$INSTDIR\plugins"
  File "/oname=clip-mask.jar" "${STAGING_DIR}/plugins/clip-mask.jar"
SectionEnd

Section "Clip Mask Viewer 0.1.0" SEC_dev_turboism_plugin_clipmask_viewer
  SetOutPath "$INSTDIR\plugins"
  File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
SectionEnd

Section "Context Menu Plugin 0.1.0" SEC_dev_turboism_plugin_context_menu
  SetOutPath "$INSTDIR\plugins"
  File "/oname=context-menu.jar" "${STAGING_DIR}/plugins/context-menu.jar"
SectionEnd

Section "Cubism Tab Filter 0.1.0" SEC_dev_turboism_plugin_cubism_tab_filter
  SetOutPath "$INSTDIR\plugins"
  File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
SectionEnd

Section "Demo Plugin 0.1.0" SEC_dev_turboism_plugin_demo
  SetOutPath "$INSTDIR\plugins"
  File "/oname=demo.jar" "${STAGING_DIR}/plugins/demo.jar"
SectionEnd

Section "Log Filter Plugin 0.1.0" SEC_dev_turboism_plugin_logfilter
  SetOutPath "$INSTDIR\plugins"
  File "/oname=log-filter.jar" "${STAGING_DIR}/plugins/log-filter.jar"
SectionEnd

Section "Mesh Read-Only Inspector Plugin 0.1.0" SEC_dev_turboism_plugin_mesh
  SetOutPath "$INSTDIR\plugins"
  File "/oname=mesh.jar" "${STAGING_DIR}/plugins/mesh.jar"
SectionEnd

Section "Palette Label Style Plugin 0.1.0" SEC_dev_turboism_plugin_palette_label_style
  SetOutPath "$INSTDIR\plugins"
  File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
SectionEnd

Section "Parameter Tools Plugin 0.1.0" SEC_dev_turboism_plugin_parameter
  SetOutPath "$INSTDIR\plugins"
  File "/oname=parameter.jar" "${STAGING_DIR}/plugins/parameter.jar"
SectionEnd

Section "Performance Overlay Plugin 0.1.0" SEC_dev_turboism_plugin_perfopt
  SetOutPath "$INSTDIR\plugins"
  File "/oname=perf-opt.jar" "${STAGING_DIR}/plugins/perf-opt.jar"
SectionEnd

Section "Physics Editor 0.1.0" SEC_dev_turboism_plugin_physics_editor
  SetOutPath "$INSTDIR\plugins"
  File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
SectionEnd

Section "Project Inspector 0.1.0" SEC_dev_turboism_plugin_project_inspector
  SetOutPath "$INSTDIR\plugins"
  File "/oname=project-inspector.jar" "${STAGING_DIR}/plugins/project-inspector.jar"
SectionEnd

Section "Project Panel Plugin 0.1.0" SEC_dev_turboism_plugin_project_panel
  SetOutPath "$INSTDIR\plugins"
  File "/oname=project-panel.jar" "${STAGING_DIR}/plugins/project-panel.jar"
SectionEnd

Section "PSD Import Plugin 0.1.0" SEC_dev_turboism_plugin_psd_import
  SetOutPath "$INSTDIR\plugins"
  File "/oname=psd-import.jar" "${STAGING_DIR}/plugins/psd-import.jar"
SectionEnd

Section "Recent Preview Plugin 0.1.0" SEC_dev_turboism_plugin_recent_preview
  SetOutPath "$INSTDIR\plugins"
  File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
SectionEnd

Section "Render Optimization Plugin 0.1.0" SEC_dev_turboism_plugin_renderopt
  SetOutPath "$INSTDIR\plugins"
  File "/oname=render-opt.jar" "${STAGING_DIR}/plugins/render-opt.jar"
SectionEnd

Section "Scene Palette Enhancer 0.1.0" SEC_dev_turboism_plugin_scene_palette_enhancer
  SetOutPath "$INSTDIR\plugins"
  File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
SectionEnd

Section "MaxRects-BSSF Layout Algorithm 0.1.0" SEC_dev_turboism_plugin_texture_atlas
  SetOutPath "$INSTDIR\plugins"
  File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
SectionEnd

Section "Texture Atlas Statistics 0.1.0" SEC_dev_turboism_plugin_texture_atlas_stats
  SetOutPath "$INSTDIR\plugins"
  File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
SectionEnd

Section "UI Theme Plugin 0.1.0" SEC_dev_turboism_plugin_uitheme
  SetOutPath "$INSTDIR\plugins"
  File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
SectionEnd

; 组件页悬停描述
!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_bounding_box} "SDK-only migration shell for the Bounding Box legacy feature."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_clipmask} "Fake-ready read-only clip-mask inspector using SDK read and UI host capabilities. Writeback and real-host placement remain future work."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_clipmask_viewer} "Read-only clip-mask duplicate checker and visualizer: Turboism tab entry, graph/table inspector window, editor selection highlight, GUID copy."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_context_menu} "SDK-only migration shell for the Context Menu legacy feature."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_cubism_tab_filter} "Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette tabs."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_demo} "Demo plugin for Turboism framework validation."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_logfilter} "SDK-only log palette toolbar filtering through Turboism UI host services."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_mesh} "Fake-ready read-only mesh/deformer inspector using SDK read and context-source capabilities. Writeback remains blocked."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_palette_label_style} "Label text and background colors for Deformer, Part, and Parameter palette entries via context menus."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_parameter} "Parameter CSV import/export plus typed batch binding inversion and transfer workflows."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_perfopt} "FPS overlay toggle action/menu and lifecycle provider shell."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_physics_editor} "Adds Physics Settings group select-all and reopen retention."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_project_inspector} "Developer Preview window showing the active Cubism project and workspace through the Turboism SDK."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_project_panel} "SDK-only migration shell for the Project Panel legacy feature."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_psd_import} "SDK-only migration shell for the PSD Import legacy feature."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_recent_preview} "Captures bounded preview thumbnails for recent project files and contributes them to the Recent Files hover popup."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_renderopt} "Fake-ready render status overlay using SDK read and UI host capabilities. Actual render interception remains adapter/hook work."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_scene_palette_enhancer} "Sorts and manually reorders items in the Cubism Scene palette."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas} "Registers the MaxRects-BSSF texture-atlas packing algorithm with parallel search."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_texture_atlas_stats} "Shows the total and current-texture model-image counts in the native texture-atlas editor window."
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_dev_turboism_plugin_uitheme} "Legacy-compatible theme packages, built-in themes, theme manager workflow, and exact-version Cubism appearance application."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; 按模式设置全部插件 Section 的选中状态（$0: 1 = 选中, 0 = 取消）
Function SetPluginSectionsSelected
  SectionGetFlags ${SEC_dev_turboism_plugin_bounding_box} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_bounding_box} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_clipmask} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_clipmask} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_clipmask_viewer} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_clipmask_viewer} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_context_menu} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_context_menu} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_cubism_tab_filter} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_cubism_tab_filter} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_demo} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_demo} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_logfilter} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_logfilter} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_mesh} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_mesh} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_palette_label_style} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_palette_label_style} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_parameter} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_parameter} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_perfopt} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_perfopt} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_physics_editor} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_physics_editor} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_project_inspector} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_project_inspector} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_project_panel} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_project_panel} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_psd_import} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_psd_import} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_recent_preview} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_recent_preview} $1
  SectionGetFlags ${SEC_dev_turboism_plugin_renderopt} $1
  IntOp $1 $1 & ${SECTION_OFF}
  ${If} $0 == 1
    IntOp $1 $1 | ${SF_SELECTED}
  ${EndIf}
  SectionSetFlags ${SEC_dev_turboism_plugin_renderopt} $1
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
  SectionGetFlags ${SEC_dev_turboism_plugin_bounding_box} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.bounding-box"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.bounding-box"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_clipmask} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.clipmask"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.clipmask"
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
  SectionGetFlags ${SEC_dev_turboism_plugin_context_menu} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.context-menu"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.context-menu"
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
  SectionGetFlags ${SEC_dev_turboism_plugin_demo} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.demo"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.demo"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_logfilter} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.logfilter"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.logfilter"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_mesh} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.mesh"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.mesh"
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
  SectionGetFlags ${SEC_dev_turboism_plugin_parameter} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.parameter"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.parameter"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_perfopt} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.perfopt"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.perfopt"
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
  SectionGetFlags ${SEC_dev_turboism_plugin_project_inspector} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.project-inspector"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.project-inspector"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_project_panel} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.project-panel"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.project-panel"
    ${EndIf}
  ${EndIf}
  SectionGetFlags ${SEC_dev_turboism_plugin_psd_import} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.psd-import"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.psd-import"
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
  SectionGetFlags ${SEC_dev_turboism_plugin_renderopt} $1
  IntOp $2 $1 & ${SF_SELECTED}
  ${If} $2 == 0
    ${If} $uncheckedPluginIds == ""
      StrCpy $uncheckedPluginIds "dev.turboism.plugin.renderopt"
    ${Else}
      StrCpy $uncheckedPluginIds "$uncheckedPluginIds;dev.turboism.plugin.renderopt"
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

